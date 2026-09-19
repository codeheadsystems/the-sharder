package com.codeheadsystems.sharder.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The dependency check of `40-java-binding.md`.
 *
 * The artifact requires `java.base` and nothing else, under
 * `docs/design/adr/0032-dependency-free-json-and-canonicalisation.md`. A dependency added for a
 * convenience inside the library reaches every consumer as a version constraint they did not
 * choose, and the first one costs nothing visible, so the rule is enforced where it is observable:
 * the published descriptor. A test dependency reaches no consumer and appears in no scope this
 * check reads.
 */
abstract class VerifyPomTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pom: RegularFileProperty

    @TaskAction
    fun verify() {
        val file = pom.get().asFile
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(file)
        val reaching = mutableListOf<String>()
        val nodes = document.getElementsByTagName("dependency")
        for (index in 0 until nodes.length) {
            val dependency = nodes.item(index) as Element
            val scope = text(dependency, "scope") ?: "compile"
            if (scope == "compile" || scope == "runtime") {
                reaching += "${text(dependency, "groupId")}:${text(dependency, "artifactId")}" +
                    ":${text(dependency, "version")} at $scope scope"
            }
        }
        if (reaching.isNotEmpty()) {
            reaching.forEach { logger.error("  $it") }
            throw GradleException(
                "${file.name} carries ${reaching.size} dependency(s) reaching a consumer"
            )
        }
        logger.lifecycle("verifyPomDependencies: ${file.name} carries no consumer-reaching dependency")
    }

    private fun text(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        return if (nodes.length == 0) null else nodes.item(0).textContent.trim()
    }
}
