package com.varabyte.kobweb.cli.create.freemarker

import com.varabyte.kobweb.cli.common.informInfo
import com.varabyte.kobweb.cli.common.processing
import com.varabyte.kobweb.cli.common.queryUser
import com.varabyte.kobweb.cli.common.template.Instruction
import com.varabyte.kobweb.cli.common.wildcardToRegex
import com.varabyte.kobweb.cli.create.freemarker.methods.FileToPackageMethod
import com.varabyte.kobweb.cli.create.freemarker.methods.FileToTitleMethod
import com.varabyte.kobweb.cli.create.freemarker.methods.IsNotEmptyMethod
import com.varabyte.kobweb.cli.create.freemarker.methods.IsPackageMethod
import com.varabyte.kobweb.cli.create.freemarker.methods.IsYesNoMethod
import com.varabyte.kobweb.cli.create.freemarker.methods.PackageToPathMethod
import com.varabyte.kobweb.cli.create.freemarker.methods.YesNoToBoolMethod
import com.varabyte.kobweb.common.error.KobwebException
import com.varabyte.kobweb.common.path.toUnixSeparators
import com.varabyte.kotter.runtime.Session
import freemarker.cache.NullCacheStorage
import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateExceptionHandler
import freemarker.template.TemplateMethodModelEx
import java.io.File
import java.io.FileWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.notExists

private fun String.process(cfg: Configuration, model: Map<String, Any>): String {
    val reader = StringReader(this)
    val writer = StringWriter()
    Template("unused", reader, cfg).process(model, writer)
    return writer.buffer.toString()
}

class FreemarkerState(private val src: Path, private val dest: Path) {
    private val model = mutableMapOf(
        "projectFolder" to dest.name,

        // region Validators
        "isNotEmpty" to IsNotEmptyMethod(),
        "isPackage" to IsPackageMethod(),
        "isYesNo" to IsYesNoMethod(),
        // endregion

        // region Converters
        "fileToTitle" to FileToTitleMethod(),
        "fileToPackage" to FileToPackageMethod(),
        "packageToPath" to PackageToPathMethod(),
        "yesNoToBool" to YesNoToBoolMethod(),
        // endregion
    )

    // See also: https://freemarker.apache.org/docs/pgui_quickstart_all.html
    private val cfg = Configuration(Configuration.VERSION_2_3_31).apply {
        setDirectoryForTemplateLoading(src.toFile())
        // Kobweb doesn't serve templates - it just runs through files once. No need to cache.
        cacheStorage = NullCacheStorage()
        defaultEncoding = "UTF-8"
        templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        logTemplateExceptions = false
        wrapUncheckedExceptions = true
        fallbackOnNullLoopVariable = false
    }

    private fun Session.process(instructions: Iterable<Instruction>) {
        for (inst in instructions) {
            val useInstruction = inst.condition?.process(cfg, model)?.toBoolean() ?: true
            if (!useInstruction) continue

            when (inst) {
                is Instruction.Group -> {
                    process(inst.instructions)
                }

                is Instruction.Inform -> {
                    val message = inst.message.process(cfg, model)
                    informInfo(message)
                }

                is Instruction.QueryVar -> {
                    val default = inst.default?.process(cfg, model)
                    val answer = queryUser(inst.prompt, default, validateAnswer = { value ->
                        (model[inst.validation] as? TemplateMethodModelEx)?.exec(listOf(value))?.toString()
                    })
                    val finalAnswer = inst.transform?.let { transform ->
                        val modelWithValue = model.toMutableMap()
                        modelWithValue["value"] = answer
                        transform.process(cfg, modelWithValue)
                    } ?: answer
                    model[inst.name] = finalAnswer
                }

                is Instruction.DefineVar -> {
                    model[inst.name] = inst.value.process(cfg, model)
                }

                is Instruction.ProcessFreemarker -> {
                    processing("Processing templates") {
                        val srcFile = src.toFile()
                        val filesToProcess = mutableListOf<File>()
                        srcFile.walkBottomUp().forEach { file ->
                            if (file.extension == "ftl") {
                                filesToProcess.add(file)
                            }
                        }
                        filesToProcess.forEach { templateFile ->
                            val template = cfg.getTemplate(templateFile.toRelativeString(srcFile))
                            FileWriter(templateFile.path.removeSuffix(".ftl")).use { writer ->
                                template.process(model, writer)
                            }
                            templateFile.delete()
                        }
                    }
                }

                is Instruction.Move -> {
                    val to = inst.to.process(cfg, model)
                    processing(inst.description ?: "Moving \"${inst.from}\" to \"$to\"") {
                        val matcher = inst.from.wildcardToRegex()
                        val srcFile = src.toFile()
                        val filesToMove = mutableListOf<File>()
                        srcFile.walkBottomUp().forEach { file ->
                            // Matcher expects *nix paths; make sure this check works on Windows
                            if (matcher.matches(file.toRelativeString(srcFile).toUnixSeparators())) {
                                filesToMove.add(file)
                            }
                        }
                        val destPath = src.resolve(to)
                        if (destPath.notExists()) {
                            destPath.createDirectories()
                        } else if (destPath.isRegularFile()) {
                            throw KobwebException("Cannot move files into target that isn't a directory")
                        }
                        filesToMove.forEach { fileToMove ->
                            Files.move(fileToMove.toPath(), destPath.resolve(fileToMove.name))
                        }
                    }
                }

                is Instruction.Rename -> {
                    val name = inst.name.process(cfg, model)
                    processing(inst.description ?: "Renaming \"${inst.file}\" to \"$name\"") {
                        val srcFile = src.toFile()
                        val fileToRename = srcFile.resolve(inst.file)
                        if (!fileToRename.exists()) {
                            throw KobwebException("Cannot rename a file (${inst.file}) because it does not exist")
                        }

                        // If the rename isn't actually changing anything, technically we're done
                        if (fileToRename.name != name) {
                            val targetFile = fileToRename.resolveSibling(name)
                            if (targetFile.exists()) {
                                throw KobwebException("Cannot rename a file (${inst.file}) because the rename target ($targetFile) already exists")
                            }

                            fileToRename.renameTo(targetFile)
                        }
                    }
                }

                is Instruction.Delete -> {
                    processing(inst.description ?: "Deleting \"${inst.files}\"") {
                        val deleteMatcher = inst.files.wildcardToRegex()

                        val srcFile = src.toFile()
                        val filesToDelete = mutableListOf<File>()
                        srcFile.walkBottomUp().forEach { file ->
                            // Matcher expects *nix paths; make sure this check works on Windows
                            val relativePath = file.toRelativeString(srcFile).toUnixSeparators()
                            if (deleteMatcher.matches(relativePath)) {
                                filesToDelete.add(file)
                            }
                        }
                        filesToDelete.forEach { fileToDelete -> fileToDelete.deleteRecursively() }
                    }
                }
            }
        }

    }

    fun execute(app: Session, instructions: List<Instruction>) {
        app.apply {
            process(instructions)
            processing("Nearly finished. Populating final project") {
                val srcFile = src.toFile()
                val files = mutableListOf<File>()
                srcFile.walkBottomUp().forEach { file ->
                    if (file.isFile) {
                        files.add(file)
                    }
                }

                files.forEach { file ->
                    val subPath = file.parentFile.toRelativeString(srcFile)
                    val destPath = dest.resolve(subPath)
                    if (destPath.notExists()) {
                        destPath.createDirectories()
                    }

                    Files.copy(file.toPath(), destPath.resolve(file.name))
                }
            }
        }
    }
}