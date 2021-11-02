package ru.yoomoney.gradle.plugins.backend.build.test

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.testng.TestNGOptions
import ru.yoomoney.gradle.plugins.backend.build.JavaExtension

/**
 * Конфигрурует unit тесты и компонетные тесты
 *
 * @author Valerii Zhirnov
 * @since 17.04.2019
 */
class TestConfigurer {
    companion object {
        const val ALL_TESTS_TASK_NAME = "unitAndComponentTest"
        private const val UNIT_TESTS_TASK_NAME = "test"
        private const val COMPONENT_TESTS_TASK_NAME = "componentTest"
        private const val DEPRECATED_COMPONENT_TESTS_TASK_NAME = "slowTest"
    }

    fun init(target: Project) {
        val extension = target.extensions.getByType(JavaExtension::class.java)

        val allTestTaskNames = mutableListOf(UNIT_TESTS_TASK_NAME)

        configureUnitTestTasks(target, extension)

        if (hasComponentTest(target)) {
            configureComponentTestTasks(target, extension)
            allTestTaskNames.add(COMPONENT_TESTS_TASK_NAME)
        }

        // задача запуска всех существующих тестов
        target.tasks.create(ALL_TESTS_TASK_NAME).apply {
            dependsOn(allTestTaskNames)
        }

        target.tasks.withType(Test::class.java).forEach {
            it.reports.junitXml.destination = target.file("${target.property("testResultsDir")}/${it.name}")
            it.reports.junitXml.isOutputPerTestCase = true
            it.reports.html.destination = target.file("${target.buildDir}/reports/${it.name}")
        }
    }

    private fun hasComponentTest(target: Project): Boolean {
        return target.file("src/$COMPONENT_TESTS_TASK_NAME").exists() || target.file("src/$DEPRECATED_COMPONENT_TESTS_TASK_NAME").exists()
    }

    private fun configureUnitTestTasks(target: Project, extension: JavaExtension) {
        // задача запуска Junit тестов
        target.tasks.create("testJunit", Test::class.java).apply {
            systemProperty("file.encoding", "UTF-8")
        }

        // задача запуска TestNG тестов
        target.tasks.create("testTestNG", Test::class.java).apply {
            useTestNG()
            systemProperty("file.encoding", "UTF-8")
            options {
                it as TestNGOptions
                it.parallel = extension.test.parallel
                it.threadCount = extension.test.threadCount
                it.listeners = extension.test.listeners
            }
        }

        // задача запуска TestNG и Junit тестов
        target.tasks.maybeCreate(UNIT_TESTS_TASK_NAME).apply {
            dependsOn("testJunit", "testTestNG")
        }
    }

    private fun configureComponentTestTasks(target: Project, extension: JavaExtension) {
        val sourceSet = setUpComponentTestsSourceSet(target)

        // задача запуска компонентных Junit тестов
        target.tasks.create("${sourceSet.name}Test", Test::class.java).apply {
            systemProperty("file.encoding", "UTF-8")
            testClassesDirs = sourceSet.output.classesDirs
            classpath = sourceSet.runtimeClasspath
        }

        val overwriteTestReportsTask = target.tasks.create("overwriteTestReports", OverwriteTestReportsTask::class.java).apply {
            xmlReportsPath = "${target.property("testResultsDir")}/${sourceSet.name}TestNg"
        }

        // задача запуска компонентных TestNG тестов
        target.tasks.create("${sourceSet.name}TestNg", Test::class.java).apply {
            useTestNG()
            systemProperty("file.encoding", "UTF-8")
            options {
                it as TestNGOptions
                it.parallel = extension.componentTest.parallel
                it.threadCount = extension.componentTest.threadCount
                it.listeners = extension.componentTest.listeners
            }

            testClassesDirs = sourceSet.output.classesDirs
            classpath = sourceSet.runtimeClasspath

            finalizedBy(overwriteTestReportsTask)
        }

        // задача запуска компонентных TestNG и Junit тестов
        target.tasks.create("componentTest").apply {
            dependsOn("${sourceSet.name}Test", "${sourceSet.name}TestNg")
        }

        val compileTestJavaTaskName = "compile${Character.toUpperCase(sourceSet.name[0])}${sourceSet.name.substring(1)}Java"

        target.tasks.getByName("check").apply {
            dependsOn += compileTestJavaTaskName
        }
    }

    private fun setUpComponentTestsSourceSet(target: Project): SourceSet {
        val chosenSourceSetName = if (target.file("src/$COMPONENT_TESTS_TASK_NAME").exists()) COMPONENT_TESTS_TASK_NAME else DEPRECATED_COMPONENT_TESTS_TASK_NAME

        // Создание и сохранение SourceSet для компонентных тестов в глобальную переменную с помощью механизма convention
        target.convention.getPlugin(JavaPluginConvention::class.java).apply {
            val componentTest = sourceSets.create(chosenSourceSetName)
            componentTest.compileClasspath += sourceSets.getByName("main").output + sourceSets.getByName("test").output
            componentTest.runtimeClasspath += sourceSets.getByName("main").output + sourceSets.getByName("test").output
            componentTest.java { it.srcDir(target.file("src/$chosenSourceSetName/java")) }
            componentTest.resources {
                it.srcDir(target.file("src/$chosenSourceSetName/resources"))
            }
        }.sourceSets.getAt(chosenSourceSetName)

        target.configurations
            .getByName("${chosenSourceSetName}Compile")
            .extendsFrom(target.configurations.getByName("testCompile"))
        target.configurations
            .getByName("${chosenSourceSetName}Runtime")
            .extendsFrom(target.configurations.getByName("testRuntime"))

        // Получение SourceSet для компонентных тестов из глобальной переменной с помощью механизма convention
        return target.convention.getPlugin(JavaPluginConvention::class.java).sourceSets.getAt(chosenSourceSetName)
    }
}
