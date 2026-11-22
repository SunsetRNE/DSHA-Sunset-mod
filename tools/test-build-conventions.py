#!/usr/bin/env python3
"""共享构建约定的静态门禁；不启动 Gradle。

--emit-gradle-fixtures DIR 只在仓库外生成 Android library / Java / Kotlin JVM
真实编译测试夹具及 init 脚本，供主会话用 dsha-gradle 串行、离线验证。
--emit-test-options-negative-init FILE 只生成仓库外 JUnit 分类/框架过滤或资源源根负控；
--case exclude-category|include-category|junit-platform|external-resources，--task 指定真实 Test 路径。
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LOGIC = ROOT / "build-logic"
WORKFLOW = ROOT / ".github/workflows/_node-unit-tests.yml"


def text(path):
    return path.read_text(encoding="utf-8")


def require(source, *patterns):
    for pattern in patterns:
        if re.search(pattern, source, re.MULTILINE) is None:
            raise ValueError("BUILD_CONVENTION_MISSING: " + pattern)


def test_contract(source):
    require(source,
            r"testTask\.maxHeapSize = '2048m'", r"testTask\.maxParallelForks = 1$",
            r"testImplementation 'junit:junit:4\.13\.2'",
            r"file\('src/main/assets'\)\.absolutePath",
            r"project\.ext\.has\('dshaTestAssetsDirectory'\)",
            r"configuredAssetsDirectory\.isAbsolute\(\)", r"configuredAssetsDirectory\.isDirectory\(\)",
            r"testAssetsDirectory = configuredAssetsDirectory\.absolutePath",
            r"testTask\.inputs\.property 'dsha\.sourceOnlyTests', sourceOnlyTests",
            r"testTask\.systemProperty 'dsha\.sourceOnlyTests', sourceOnlyTests",
            r"sourceOnlyTests in \['true', 'false'\]",
            r"rustRequiredProperty in \['true', 'false'\]",
            r"testTask\.systemProperty 'dsha\.rust\.hostLib', rustHostLibProperty\.toString\(\)",
            r"testTask\.systemProperty 'dsha\.rust\.required', rustRequiredProperty",
            r"else if \(rustRequiredProperty == 'true'\)",
            r"testTask\.systemProperty 'dsha\.rust\.required', 'true'",
            r"testTask\.jvmArgs '--add-opens=java\.base/java\.io=ALL-UNNAMED'",
            r"System\.getenv\('DSHA_ROBOLECTRIC_DEPENDENCY_DIR'\)",
            r"testTask\.systemProperty 'robolectric\.offline', 'true'",
            r"testTask\.systemProperty 'robolectric\.dependency\.dir', frameworkDirectory")


def android_contract(source):
    require(source, r"coreLibraryDesugaringEnabled true",
            r"sourceCompatibility JavaVersion\.VERSION_17", r"targetCompatibility JavaVersion\.VERSION_17",
            r"unitTests\.includeAndroidResources = true",
            r"apply from: rootProject\.file\('build-logic/kotlin-target\.gradle'\)",
            r"coreLibraryDesugaring 'com\.android\.tools:desugar_jdk_libs:2\.0\.4'",
            r"testImplementation 'org\.robolectric:robolectric:4\.15\.1'",
            r"apply from: rootProject\.file\('build-logic/test\.gradle'\)")


def android_library_contract(source):
    require(source, r"apply plugin: 'com\.android\.library'",
            r"compileSdk\s*\{\s*version = release\(37\)",
            r"defaultConfig\s*\{\s*minSdk 30\s*\}",
            r"apply from: rootProject\.file\('build-logic/android\.gradle'\)")


def kotlin_target_contract(source):
    require(source, r"extensions\.getByName\('kotlin'\)",
            r"kotlinExtension\.getClass\(\)\.getClassLoader\(\)\.loadClass\(",
            r"'org\.jetbrains\.kotlin\.gradle\.dsl\.JvmTarget'",
            r"java\.lang\.Enum\.valueOf\(jvmTargetType, 'JVM_17'\)",
            r"kotlinExtension\.compilerOptions\.jvmTarget\.set\(jvmTarget17\)")


def junit_options_contract(hooks, audit):
    require(hooks, r"def actualJUnitOptions = testTask\.options",
            r"if \(!\(actualJUnitOptions instanceof org\.gradle\.api\.tasks\.testing\.junit\.JUnitOptions\)\)\s*"
            r'throw new GradleException\("DSHA_TEST_FRAMEWORK:',
            r"\+ actualJUnitOptions\.includeCategories\.collect \{ 'junit4-include-category:' \+ it \}",
            r"\+ actualJUnitOptions\.excludeCategories\.collect \{ 'junit4-exclude-category:' \+ it \}",
            r"'--filters-json', groovy\.json\.JsonOutput\.toJson\(actualFilters\)")
    require(audit, r"def actualJUnitOptions = test\.options",
            r"if \(!\(actualJUnitOptions instanceof org\.gradle\.api\.tasks\.testing\.junit\.JUnitOptions\)\)\s*"
            r'throw new GradleException\("DSHA_TEST_FRAMEWORK:',
            r"if \(!actualJUnitOptions\.includeCategories\.isEmpty\(\) \|\| "
            r"!actualJUnitOptions\.excludeCategories\.isEmpty\(\)\)\s*"
            r'throw new GradleException\("DSHA_TEST_CATEGORIES:')


def resource_roots_contract(audit):
    require(audit,
            r"def auditResourceRoots = \{ resourceModule, sourceSetName, resourceKind, actualRoots ->",
            r'def standardRelativeRoot = "src/\$\{sourceSetName\}/\$\{resourceKind\}"',
            r"resourceModule\.projectDir\.absoluteFile\.toPath\(\)\.normalize\(\)\s*"
            r"\.resolve\(standardRelativeRoot\)\.normalize\(\)",
            r"resourceModule\.projectDir\.canonicalFile\.toPath\(\)\s*"
            r"\.resolve\(standardRelativeRoot\)\.normalize\(\)",
            r"if \(resourceRoot\.absoluteFile\.toPath\(\)\.normalize\(\) != declaredStandardRoot\s*"
            r"\|\| resourceRoot\.canonicalFile\.toPath\(\) != canonicalStandardRoot\)\s*"
            r'throw new GradleException\("DSHA_RESOURCE_SOURCE_ROOT:',
            r"def resourceSourceSets = androidModule \? module\.extensions\.getByName\('android'\)\.sourceSets\s*"
            r": module\.extensions\.getByName\('sourceSets'\)",
            r"resourceSourceSets\.each \{ sourceSet ->",
            r"auditResourceRoots\(module, sourceSet\.name, 'resources', sourceSet\.resources\.srcDirs\)",
            r"if \(androidModule\) auditResourceRoots\(module, sourceSet\.name, 'res', sourceSet\.res\.srcDirs\)")


def source_inputs_contract(source, verify, hook, root):
    require(root, r"apply from: rootProject\.file\('build-logic/source-inputs\.gradle'\)")
    require(verify, r"rootProject\.ext\.dshaAuditSourceInputs\(false\)")
    require(hook, r"rootProject\.ext\.dshaWriteCompileProof\(testTask, new File\(contractState\)\)",
            r"'--compile-proof', compileProof\.absolutePath")
    require(source, r"'--compile-inputs'", r"sourceSet\.java\.srcDirs", r"sourceSet\.kotlin\.srcDirs",
            r"kotlin\.sourceSets\.each", r"actualPaths != expectedPaths",
            r"file\.canonicalFile\.toPath\(\) != expectedCanonical",
            r"androidComponents\.onVariants\(androidComponents\.selector\(\)\.all\(\),",
            r"as org\.gradle\.api\.Action", r"component\.computeTaskName\('compile', 'JavaWithJavac'\)",
            r"component\.sources\.java\.static\.get\(\)", r"component\.sources\.kotlin\.static\.get\(\)",
            r"task\.sources\.files : task\.source\.files", r"actualJava = task\.javaSources\.files",
            r"actualSources != existingExpected", r"kotlin && actualJava != existingJava",
            r"if \(strict \|\| !specs\.any", r"before task '\$\{it\.task\}' has completed",
            r"producer\.outputs\.files\.files != \(\[output\] as Set\)", r"spec\.files\.containsAll\(actual\)",
            r"producer\.name != component\.computeTaskName\('generate', 'BuildConfig'\)",
            r"producer\.name != context\.component\.computeTaskName\('compile', 'Aidl'\)",
            r"'dsha-compile-proof'", r"inventory: inventory", r"def row = auditCompile\(task, inventory, true\)",
            r"'--verify-compile-head'", r"kind: 'dsha-compile-execution'", r"before: start\.before, after: after",
            r"receipt\.before != row \|\| receipt\.after != row", r"start\.before != after",
            r"execution: \[file: 'compile-executions/' \+ receiptFile\.name, sha256: sha256\(receiptFile\)\]",
            r"java\.nio\.file\.StandardOpenOption\.CREATE_NEW", r"if \(isCompile\(task\)\)",
            r"task\.doFirst", r"task\.doLast", r"def before = auditCompile\(task, snapshot\(\), true\)",
            r"def after = auditCompile\(task, snapshot\(\), true\)",
            r"sourceSet\.getCompileTaskName\('kotlin'\)", r"component: componentRow\(task, context\)")


def shell_blocks(source):
    blocks = []
    lines = source.splitlines()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"(\s*)run: \|", line)
        if not match:
            continue
        indent = len(match.group(1)) + 2
        body = []
        for candidate in lines[index + 1:]:
            if candidate.strip() and len(candidate) - len(candidate.lstrip()) < indent:
                break
            body.append(candidate[indent:] if candidate.strip() else "")
        blocks.append("\n".join(body) + "\n")
    return blocks


def groovy_string(value):
    value = str(value)
    if any(character in value for character in "\r\n\0"):
        raise ValueError("FIXTURE_PATH_CONTROL")
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def emit_gradle_fixtures(destination):
    """仅写新目录；夹具是临时验证工程，不进 settings/source-layout 或产品依赖。"""
    destination = Path(destination).absolute()
    resolved = destination.resolve()
    if resolved.is_relative_to(ROOT) or destination.exists() or destination.is_symlink():
        raise ValueError("FIXTURE_REQUIRES_NEW_DIRECTORY_OUTSIDE_REPOSITORY")
    destination.mkdir()
    files = {
        "java/build.gradle": "ext.dshaTestAssetsDirectory = file('src/test/fixtures')\n"
                             "apply from: rootProject.file('build-logic/jvm-library.gradle')\n",
        "java/src/test/fixtures/owner.txt": "java fixture owner\n",
        "java/src/main/java/fixture/JavaValue.java":
            "package fixture; public final class JavaValue { public static int answer() { return 42; } }\n",
        "java/src/test/java/fixture/JavaConventionTest.java":
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; "
            "public class JavaConventionTest { @Test public void usesJava17AndJunit4() { "
            "assertEquals(42, JavaValue.answer()); assertEquals(\"17\", System.getProperty(\"java.specification.version\")); "
            "assertTrue(new java.io.File(System.getProperty(\"dsha.assetsDir\"), \"owner.txt\").isFile()); } }\n",
        "kotlin/build.gradle": "apply from: rootProject.file('build-logic/kotlin-jvm-library.gradle')\n",
        "kotlin/src/main/kotlin/fixture/KotlinValue.kt":
            "package fixture\nobject KotlinValue { fun answer(): Int = 42 }\n",
        "kotlin/src/test/kotlin/fixture/KotlinConventionTest.kt":
            "package fixture\nimport org.junit.Test\nimport org.junit.Assert.assertEquals\n"
            "class KotlinConventionTest { @Test fun usesPinnedKotlinAndJava17() { "
            "assertEquals(42, KotlinValue.answer()); assertEquals(\"2.2.10\", KotlinVersion.CURRENT.toString()); "
            "assertEquals(\"17\", System.getProperty(\"java.specification.version\")) } }\n",
        "android/build.gradle":
            "apply from: rootProject.file('build-logic/android-library.gradle')\n"
            "android { namespace 'fixture.android' }\n",
        "android/src/main/AndroidManifest.xml": '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />\n',
        "android/src/main/res/values/strings.xml": '<resources><string name="fixture_value">fixture resource</string></resources>\n',
        "android/src/main/java/fixture/android/AndroidValue.kt":
            "package fixture.android\nobject AndroidValue { @JvmStatic fun answer(): Int = 42 }\n",
        "android/src/test/java/fixture/android/AndroidConventionTest.java":
            "package fixture.android; import org.junit.Test; import static org.junit.Assert.*; "
            "import org.junit.runner.RunWith; import org.robolectric.RobolectricTestRunner; "
            "import org.robolectric.RuntimeEnvironment; import org.robolectric.annotation.Config; "
            "@RunWith(RobolectricTestRunner.class) @Config(sdk = 34) public class AndroidConventionTest { "
            "@Test public void builtInKotlinAndPackagedResourcesWork() { assertEquals(42, AndroidValue.answer()); "
            "assertEquals(\"fixture resource\", RuntimeEnvironment.getApplication().getString(R.string.fixture_value)); } }\n",
    }
    includes = ["gradle.settingsEvaluated { settings ->",
                "    if (settings.rootDir.canonicalPath != " + groovy_string(ROOT.resolve()) + ")",
                "        throw new GradleException('FIXTURE_WRONG_ROOT')"]
    projects = ((":conventionJava", "java"), (":conventionKotlin", "kotlin"), (":conventionAndroid", "android"))
    for project, directory in projects:
        files[directory + '/build.gradle'] = 'ext.dshaConventionFixture = true\n' + files[directory + '/build.gradle']
        includes += ["    settings.include " + groovy_string(project),
                     "    settings.project(" + groovy_string(project) + ").projectDir = new File("
                     + groovy_string(destination / directory) + ")"]
    files["include.init.gradle"] = "\n".join(includes + ["}", ""])
    for name, body in files.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    return {"directory": str(destination), "initScript": str(destination / "include.init.gradle"),
            "tasks": [":conventionJava:test", ":conventionKotlin:test", ":conventionAndroid:testDebugUnitTest",
                      "verifyDshaBuildConventions"]}


def emit_test_options_negative_init(destination, case="exclude-category", task=":app:testStandardDebugUnitTest"):
    """只生成仓库外的新 init 文件；不执行 Gradle，不改结构约定或产品源码。"""
    if case not in ("exclude-category", "include-category", "junit-platform", "external-resources"):
        raise ValueError("NEGATIVE_INIT_CASE")
    if not re.fullmatch(r":[A-Za-z][\w-]*(?::[A-Za-z][\w-]*)+", task):
        raise ValueError("NEGATIVE_INIT_TASK")
    destination = Path(destination).absolute()
    if destination.resolve().is_relative_to(ROOT) or destination.exists() or destination.is_symlink():
        raise ValueError("NEGATIVE_INIT_REQUIRES_NEW_FILE_OUTSIDE_REPOSITORY")
    if case == 'external-resources':
        mutation = '\n'.join((
            '        def resourceProject = testTask.project',
            "        def android = resourceProject.extensions.findByName('android')",
            "        def sourceSets = android != null ? android.sourceSets : resourceProject.extensions.getByName('sourceSets')",
            "        sourceSets.getByName('test').resources.srcDir('/tmp/dsha-external-resources-fixture')",
        ))
        expected_failure = 'DSHA_RESOURCE_SOURCE_ROOT'
    elif case == "junit-platform":
        mutation = "        testTask.useJUnitPlatform()"
        expected_failure = "DSHA_TEST_FRAMEWORK"
    else:
        method = "excludeCategories" if case == "exclude-category" else "includeCategories"
        mutation = "\n".join(("        testTask.useJUnit { junitOptions ->",
                              "            junitOptions." + method + "('fixture.HiddenCategory')",
                              "        }"))
        expected_failure = "DSHA_TEST_CATEGORIES"
    source = "\n".join((
        "// 真实 Gradle 配置负控：根审计应先拒绝分类/框架变化，不执行 Test。",
        "gradle.projectsEvaluated {",
        "    if (gradle.rootProject.projectDir.canonicalPath != " + groovy_string(ROOT.resolve()) + ")",
        "        throw new GradleException('NEGATIVE_INIT_WRONG_ROOT')",
        "    def selected = gradle.rootProject.allprojects.collectMany { module ->",
        "        module.tasks.withType(org.gradle.api.tasks.testing.Test).findAll { testTask ->",
        "            testTask.path == " + groovy_string(task),
        "        }.toList()",
        "    }",
        "    if (selected.size() != 1) throw new GradleException('NEGATIVE_INIT_TASK_MISSING')",
        "    selected.each { testTask ->",
        mutation,
        "    }",
        "}",
        "",
    ))
    with destination.open("x", encoding="utf-8") as stream:
        stream.write(source)
    return {"initScript": str(destination), "case": case, "task": task, "expectedFailure": expected_failure,
            "command": ["dsha-gradle", "--offline", "--no-parallel", "--max-workers=1", "--init-script",
                        str(destination), "verifyDshaBuildConventions"]}


def emit_source_model_init(destination, output):
    """生成只读 Gradle 模型采集脚本，交父会话串行执行；不准备资产或编译。"""
    destination, output = Path(destination).absolute(), Path(output).absolute()
    for path in (destination, output):
        if path.resolve().is_relative_to(ROOT) or path.exists() or path.is_symlink():
            raise ValueError('SOURCE_MODEL_REQUIRES_NEW_FILES_OUTSIDE_REPOSITORY')
    source = r'''def observedComponents = [:]
gradle.beforeProject { module ->
    ['com.android.application', 'com.android.library'].each { pluginId ->
        module.plugins.withId(pluginId) {
            def androidComponents = module.extensions.getByName('androidComponents')
            androidComponents.onVariants(androidComponents.selector().all(), { variant ->
                observedComponents[module.path] = (observedComponents[module.path] ?: []) + [variant]
            } as org.gradle.api.Action)
        }
    }
}
gradle.projectsEvaluated {
    if (gradle.rootProject.projectDir.canonicalPath != ROOT_PATH)
        throw new GradleException('SOURCE_MODEL_WRONG_ROOT')
    gradle.rootProject.tasks.register('dumpDshaSourceModel') {
        doLast {
            def observe = { Closure action ->
                try { return action() } catch (Exception error) {
                    return [error: error.getClass().name, message: error.message]
                }
            }
            def files = { collection -> collection.files.collect { it.absolutePath }.sort() }
            def modules = gradle.rootProject.allprojects.findAll { it != gradle.rootProject }.collect { module ->
                def android = module.extensions.findByName('android')
                def kotlin = module.extensions.findByName('kotlin')
                def sets = android != null ? android.sourceSets : module.extensions.findByName('sourceSets')
                def sourceSets = sets == null ? [] : sets.collect { sourceSet ->
                    [name: sourceSet.name, type: sourceSet.getClass().name,
                     java: observe { sourceSet.java.srcDirs.collect { it.absolutePath }.sort() },
                     kotlin: observe { sourceSet.kotlin.srcDirs.collect { it.absolutePath }.sort() },
                     resources: observe { sourceSet.resources.srcDirs.collect { it.absolutePath }.sort() }]
                }
                def kotlinSets = observe { kotlin == null ? [] : kotlin.sourceSets.collect { sourceSet ->
                    [name: sourceSet.name, type: sourceSet.getClass().name,
                     kotlin: sourceSet.kotlin.srcDirs.collect { it.absolutePath }.sort()]
                } }
                def compiles = module.tasks.findAll { task ->
                    task instanceof org.gradle.api.tasks.compile.JavaCompile ||
                    task.getClass().name.contains('KotlinCompile')
                }.collect { task ->
                    [task: task.path, type: task.getClass().name,
                     source: observe { files(task.source) }, sources: observe { files(task.sources) },
                     javaSources: observe { files(task.javaSources) },
                     includes: observe { task.includes.toList().sort() }, excludes: observe { task.excludes.toList().sort() },
                     dependsOn: observe { task.taskDependencies.getDependencies(task).collect { it.path }.sort() },
                     outputs: observe { files(task.outputs.files) },
                     variantName: observe { task.variantName }, sourceSetName: observe { task.sourceSetName },
                     rawSourceInputs: observe { task.getClass().getMethod('getSourceFiles$kotlin_gradle_plugin_common')
                             .invoke(task).from.collect { value -> [type: value.getClass().name, display: value.toString()] } }]
                }
                def generators = module.tasks.findAll { task ->
                    task.name.contains('BuildConfig') || task.name.contains('Aidl') || task.name.contains('UiLanguage')
                }.collect { task -> [task: task.path, type: task.getClass().name,
                        outputs: observe { files(task.outputs.files) },
                        dependsOn: observe { task.taskDependencies.getDependencies(task).collect { it.path }.sort() }] }
                def components = (observedComponents[module.path] ?: []).collectMany { variant ->
                    [variant] + variant.nestedComponents
                }.collect { component ->
                    [name: component.name, type: component.getClass().name,
                     buildType: observe { component.buildType }, productFlavors: observe { component.productFlavors },
                     javaCompile: observe { component.computeTaskName('compile', 'JavaWithJavac') },
                     kotlinCompile: observe { component.computeTaskName('compile', 'Kotlin') },
                     javaStatic: observe { component.sources.java.static.get().collect { it.asFile.absolutePath }.sort() },
                     kotlinStatic: observe { component.sources.kotlin.static.get().collect { it.asFile.absolutePath }.sort() }]
                }
                [module: module.path, directory: module.projectDir.absolutePath,
                 sourceSets: sourceSets, kotlinExtension: kotlin?.getClass()?.name,
                 kotlinSourceSets: kotlinSets, compiles: compiles, generators: generators, components: components]
            }
            // JSON 只接收纯数据；Provider/Gradle DSL 对象不能交 JsonOutput 反射 getter。
            def plain
            plain = { value ->
                if (value == null || value instanceof Number || value instanceof Boolean) return value
                if (value instanceof CharSequence) return value.toString()
                if (value instanceof File) return value.absolutePath
                if (value instanceof org.gradle.api.provider.Provider) return plain(value.orNull)
                if (value instanceof Map) return value.collectEntries { key, item -> [(key.toString()): plain(item)] }
                if (value instanceof Iterable) return value.collect { plain(it) }
                return [type: value.getClass().name, display: value.toString()]
            }
            def model = plain([schema: 1, kind: 'dsha-source-model', modules: modules])
            new File(OUTPUT_PATH).withWriter('UTF-8') { writer ->
                writer.write(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(model)) + '\n')
            }
        }
    }
}
'''.replace('ROOT_PATH', groovy_string(ROOT.resolve())).replace('OUTPUT_PATH', groovy_string(output))
    with destination.open('x', encoding='utf-8') as stream:
        stream.write(source)
    return {'initScript': str(destination), 'output': str(output),
            'command': ['dsha-gradle', '--offline', '--no-parallel', '--max-workers=1', '--init-script',
                        str(destination), 'dumpDshaSourceModel']}


def emit_source_negative_init(destination, case='java-root-replace', module=':app', compile_task=None):
    """配置期源码替换负控；源样本及 init 均在新外部目录，不运行编译。"""
    cases = ('java-root-replace', 'java-root-extra', 'kotlin-root-replace', 'kotlin-root-extra',
             'java-compile-replace', 'java-compile-filter', 'kotlin-compile-replace', 'kotlin-compile-filter')
    if case not in cases or not re.fullmatch(r':[A-Za-z][\w-]*(?::[A-Za-z][\w-]*)*', module):
        raise ValueError('SOURCE_NEGATIVE_CASE_OR_MODULE')
    destination = Path(destination).absolute()
    if destination.resolve().is_relative_to(ROOT) or destination.exists() or destination.is_symlink():
        raise ValueError('SOURCE_NEGATIVE_REQUIRES_NEW_FILE_OUTSIDE_REPOSITORY')
    fixture = destination.parent / (destination.stem + '-sources')
    if fixture.exists() or fixture.is_symlink():
        raise ValueError('SOURCE_NEGATIVE_FIXTURE_EXISTS')
    language = 'kotlin' if case.startswith('kotlin') else 'java'
    if compile_task is None:
        compile_task = module + (':compileStandardDebugKotlin' if module == ':app' and language == 'kotlin' else
                                 ':compileStandardDebugJavaWithJavac' if module == ':app' else
                                 ':compileKotlin' if language == 'kotlin' else ':compileJava')
    if not re.fullmatch(r':[A-Za-z][\w-]*(?::[A-Za-z][\w-]*)+', compile_task):
        raise ValueError('SOURCE_NEGATIVE_COMPILE_TASK')
    source_file = fixture / ('ShellQuote.kt' if language == 'kotlin' else 'Query.java')
    fixture.mkdir()
    body = ('package com.deepseekharness.app.util\nobject ShellQuote { @JvmStatic fun arg(value: String): String = value }\n'
            if language == 'kotlin' else 'package com.deepseekharness.app.util; public final class Query {}\n')
    source_file.write_text(body, encoding='utf-8')
    if '-root-' in case:
        if language == 'kotlin':
            target = "(android != null ? android.sourceSets : module.extensions.getByName('kotlin').sourceSets).getByName('main').kotlin"
        else:
            target = "(android != null ? android.sourceSets : module.extensions.getByName('sourceSets')).getByName('main').java"
        mutation = '    def selectedRoots = ' + target + '\n'
        mutation += ('    selectedRoots.setSrcDirs([' + groovy_string(fixture) + '])' if case.endswith('replace') else
                     '    selectedRoots.srcDir(' + groovy_string(fixture) + ')')
    else:
        mutation = "    def selectedCompile = module.tasks.getByPath(" + groovy_string(compile_task) + ')\n'
        if case.endswith('replace'):
            mutation += '    selectedCompile.setSource(module.files(' + groovy_string(source_file) + '))'
        elif language == 'java':
            mutation += "    selectedCompile.exclude('**/Query.java')"
        else:
            mutation += "    selectedCompile.exclude('**/ShellQuote.kt')"
    source = '\n'.join((
        '// 原门禁漏检复现：只执行配置审计，不运行 Test/Compile。', 'gradle.projectsEvaluated {',
        '    if (gradle.rootProject.projectDir.canonicalPath != ' + groovy_string(ROOT.resolve()) + ')',
        "        throw new GradleException('SOURCE_NEGATIVE_WRONG_ROOT')",
        '    def module = gradle.rootProject.project(' + groovy_string(module) + ')',
        "    def android = module.extensions.findByName('android')", mutation, '}', '',
    ))
    with destination.open('x', encoding='utf-8') as stream:
        stream.write(source)
    return {'initScript': str(destination), 'case': case, 'module': module, 'fixture': str(fixture),
            'source': str(source_file), 'compileTask': compile_task, 'expectedFailure': 'DSHA_SOURCE_INPUT',
            'command': ['dsha-gradle', '--offline', '--no-parallel', '--max-workers=1', '--init-script',
                        str(destination), 'verifyDshaBuildConventions']}


def emit_full_contract_fixture(destination, with_kotlin=False):
    """只生成可由父会话执行的合成全链工程；无 Gradle/JNI/真实 CI 声明。"""
    destination = Path(destination).absolute()
    if destination.resolve().is_relative_to(ROOT) or destination.exists() or destination.is_symlink():
        raise ValueError('FULL_FIXTURE_REQUIRES_NEW_DIRECTORY_OUTSIDE_REPOSITORY')
    destination.mkdir()
    fixture_marker = 'SYNTHETIC_GATE_FIXTURE_NO_JNI_NO_PRODUCTION_CI'
    files = {
        '.gitignore': 'build/\n**/build/\nout/\n.gradle/\nlocal.properties\n__pycache__/\n',
        'settings.gradle': "dependencyResolutionManagement { repositories { mavenCentral() } }\n"
                           "gradle.ext.kotlinVersion = '2.2.10'\nrootProject.name = 'synthetic-dsha-contract-fixture'\ninclude ':app'\n",
        'build.gradle': "apply from: rootProject.file('build-logic/source-inputs.gradle')\n"
                        "apply from: rootProject.file('build-logic/verify.gradle')\n"
                        "apply from: rootProject.file('build-logic/unit-test-contract.gradle')\n",
        'app/build.gradle': "apply from: rootProject.file('build-logic/jvm-library.gradle')\n",
        'gradle.properties': 'org.gradle.parallel=false\norg.gradle.caching=false\norg.gradle.jvmargs=-Xmx768m\n',
        'ci/source-layout.json': json.dumps({'schema': 1, 'modules': [{
            'path': ':app', 'directory': 'app', 'kind': 'jvm', 'sourceSets': ['main', 'test'],
            'formatSourceSets': ['main', 'test'], 'testSourceSets': ['test'], 'testTask': 'test',
        }]}, indent=2) + '\n',
        'SYNTHETIC-ONLY.txt': fixture_marker + '\n只有构建门禁合成样本；不证明产品测试、原生库或真实 CI 通过。\n',
        'app/src/main/java/com/deepseekharness/app/util/SyntheticValue.java':
            'package com.deepseekharness.app.util; public final class SyntheticValue { '
            'public static int answer() { return 17; } }\n',
    }
    if with_kotlin:
        files['build.gradle'] = '''buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // 仅复用真实根工程已验证的 classpath 依赖图；不应用 Android 插件。
        classpath 'com.android.tools.build:gradle:9.1.1'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${gradle.ext.kotlinVersion}"
    }
}
''' + files['build.gradle']
        files['app/build.gradle'] = "apply from: rootProject.file('build-logic/kotlin-jvm-library.gradle')\n"
        files['app/src/main/kotlin/com/deepseekharness/app/util/SyntheticKotlinValue.kt'] = (
            'package com.deepseekharness.app.util\n'
            '// 合成 Kotlin/Java 编译门禁样本，不调用产品功能。\n'
            'object SyntheticKotlinValue { fun answer(): Int = SyntheticValue.answer() }\n')
        files['app/src/test/kotlin/com/deepseekharness/app/util/SyntheticKotlinTest.kt'] = (
            'package com.deepseekharness.app.util\n'
            'import org.junit.Test\nimport org.junit.Assert.assertEquals\n'
            '// 合成 Kotlin/Java 全链样本；KGP 固定 2.2.10。\n'
            'class SyntheticKotlinTest {\n'
            '    @Test fun syntheticKotlinSharesJavaAndPinnedToolchain() {\n'
            '        assertEquals(17, SyntheticKotlinValue.answer())\n'
            '        assertEquals("2.2.10", KotlinVersion.CURRENT.toString())\n'
            '        assertEquals("17", System.getProperty("java.specification.version"))\n'
            '    }\n}\n')
    owners = {
        'runtime.AdbWheelBundleTest': ['realBundleRebuildsOnlyOmittedEntriesAndPreservesRestoredWheel'],
        'runtime.RuntimeToolsRegistryAndroidTest': [
            'completeProductionChainAppliesAllSpecializedStagesAndIsRepeatable',
            'lanRetirementRejectsModuleDriftAndPreservesOriginalBytes',
            'lanRetirementRejectsUnknownVersionAndContractDrift',
            'oldLanExactReplacementAndIdempotenceRemainStrict',
            'previousRuntimeGenerationCannotReceiveCurrentOverlayInPlace',
        ],
        'util.ManagedPatchRegistryIntegrationTest': ['everyActiveModuleRepairsAsAWholeAndRetiredUiNeverRuns'],
        'util.RustDigestDifferentialTest': ['syntheticRequiredFlagAndOpaqueBytesDoNotInvokeJni'],
    }
    for owner, methods in owners.items():
        package, name = ('com.deepseekharness.app.' + owner).rsplit('.', 1)
        body = [f'package {package};', 'import org.junit.Test;', 'import static org.junit.Assert.*;',
                '// 合成门禁样本：复用身份仅为验证聚合器，不执行产品夹具/JNI。', f'public class {name} {{']
        for method in methods:
            body.append(f'@Test public void {method}() throws Exception {{')
            body.append('assertEquals(17, com.deepseekharness.app.util.SyntheticValue.answer());')
            if owner.endswith('RustDigestDifferentialTest'):
                body += ['assertEquals("true", System.getProperty("dsha.rust.required"));',
                         'assertEquals("' + fixture_marker + '", java.nio.file.Files.readString('
                         'java.nio.file.Path.of(System.getProperty("dsha.rust.hostLib"))));']
            body.append('}')
        files['app/src/test/java/' + package.replace('.', '/') + '/' + name + '.java'] = '\n'.join(body + ['}', ''])
    # 使用真实共享缓存和全局构建锁入口；此脚本自身不启动 Gradle。
    files['build.sh'] = '#!/usr/bin/env bash\nset -eu\nexec bash ' + shlex.quote(str(ROOT / 'gradlew')) \
        + ' -p ' + shlex.quote(str(destination)) + ' "$@"\n'
    files['tools/prepare-synthetic.py'] = r'''#!/usr/bin/env python3
"""仅外部临时工程：准备合成 Git/plan/run/baseline，不执行 Gradle/JNI。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import unit_test_contract as contract
MARKER = 'SYNTHETIC_GATE_FIXTURE_NO_JNI_NO_PRODUCTION_CI'
if (ROOT / 'SYNTHETIC-ONLY.txt').read_text().splitlines()[0] != MARKER:
    raise SystemExit('SYNTHETIC_MARKER_REQUIRED')
if (ROOT / '.git').exists() or (ROOT / 'out/unit-run').exists():
    raise SystemExit('SYNTHETIC_ALREADY_PREPARED_CREATE_A_NEW_FIXTURE')
for command in (
    ['git', 'init', '-b', 'main'], ['git', 'config', 'user.name', 'Synthetic gate fixture'],
    ['git', 'config', 'user.email', 'synthetic-fixture@invalid.example'],
    ['git', 'add', '--', '.'], ['git', 'commit', '-m', 'test: synthetic gate inputs only; no production evidence'],
):
    subprocess.run(command, cwd=ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
commit = contract.head(ROOT)
plan = contract.plan(ROOT)
contract.write_json(ROOT / 'out/unit-plan.json', plan)
library = ROOT / 'out/synthetic/libdsha_rs.so'
library.parent.mkdir(parents=True)
library.write_bytes(MARKER.encode())
run_id = '900000001'
receipt = {'kind': 'dsha-rust-native', 'target': 'host', 'commit': commit, 'runId': run_id, 'runAttempt': '1',
           'syntheticOnly': True, 'notice': MARKER,
           'artifact': {'artifactFile': library.name, 'size': library.stat().st_size,
                        'sha256': hashlib.sha256(library.read_bytes()).hexdigest()}}
contract.write_json(ROOT / 'out/synthetic/receipt.json', receipt)
identities = sorted([row['classname'], row['method']] for source in plan['sources'] for row in source['tests'])
# 仅验证严格 schema；以下 CI 回执是显著标记的伪造测试样本，不是 GitHub 观测。
baseline = {'schema': 1, 'kind': 'dsha-unit-test-baseline', 'status': 'frozen',
            'syntheticOnly': True, 'notice': MARKER + ': fabricated CI fixture, never production evidence',
            'provenance': {'databaseId': 900000001, 'attempt': 1, 'headSha': commit,
                           'status': 'completed', 'conclusion': 'success',
                           'url': 'https://github.com/synthetic-fixture/not-a-real-ci-run/actions/runs/900000001',
                           'syntheticOnly': True},
            'identities': identities, 'tests': len(identities), 'failures': 0, 'errors': 0, 'skipped': 0,
            'requiredFixtureTestsPassed': 7, 'sources': plan['sources']}
contract.write_json(ROOT / 'out/synthetic/baseline.json', baseline)
contract.begin(ROOT, ROOT / 'out/unit-plan.json', ROOT / 'out/unit-run', run_id, '1',
               rust_library=library, rust_receipt=ROOT / 'out/synthetic/receipt.json')
print(json.dumps({'syntheticOnly': True, 'notice': MARKER, 'root': str(ROOT), 'commit': commit,
                  'runId': run_id, 'runAttempt': '1', 'tests': len(identities),
                  'state': str(ROOT / 'out/unit-run'), 'library': str(library)}, indent=2))
'''
    files['README.md'] = '\n'.join((
        '# 合成构建门禁 smoke，仅测试脚本接口', '', fixture_marker, '',
        ('这里的九个 JUnit 身份（八个 Java + 一个 Kotlin）' if with_kotlin else '这里的八个 Java JUnit 身份')
        + '、opaque 假 .so、Rust receipt 与 fake CI baseline 均为合成样本。',
        '不加载 JNI，不执行产品夹具，不是产品完整测试，也不是 GitHub CI 成功证据。', '',
        '父会话串行执行：', '', '```sh',
        'python3 tools/prepare-synthetic.py',
        'export DSHA_UNIT_TEST_STATE="$PWD/out/unit-run"',
        'PATH=/opt/jdk17/bin:$PATH dsha-gradle --offline --no-parallel --max-workers=1 --rerun-tasks '
        ':app:test verifyDshaBuildConventions -Pdsha.sourceOnlyTests=false -Pdsha.rust.required=true '
        '"-Pdsha.rust.hostLib=$PWD/out/synthetic/libdsha_rs.so"',
        'python3 tools/unit_test_contract.py verify --root "$PWD" --state out/unit-run '
        '--run-id 900000001 --run-attempt 1 --baseline out/synthetic/baseline.json --output out/synthetic/aggregate.json',
        'unset DSHA_UNIT_TEST_STATE', '```', '',
        '临时 Git commit 只覆盖本工程的合成源码、工具和复制约定。build.sh 转交原仓库固定 wrapper，',
        '由 dsha-gradle 持有共享锁。所有报告保留于 out/ 与 app/build/，不复制到产品 ci/。', '',
    ))
    for name, body in files.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    logic_files = ['source-inputs.gradle', 'verify.gradle', 'unit-test-contract.gradle', 'jvm-library.gradle', 'test.gradle']
    if with_kotlin:
        logic_files += ['kotlin-jvm-library.gradle', 'kotlin-target.gradle']
    for name in logic_files:
        target = destination / 'build-logic' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(LOGIC / name, target)
    for name in ('source_layout.py', 'unit_test_contract.py', 'git_guard.py'):
        shutil.copyfile(ROOT / 'tools' / name, destination / 'tools' / name)
    metadata = destination / 'gradle/verification-metadata.xml'
    metadata.parent.mkdir()
    shutil.copyfile(ROOT / 'gradle/verification-metadata.xml', metadata)
    (destination / 'build.sh').chmod(0o755)
    return {'directory': str(destination), 'syntheticOnly': True, 'withKotlin': with_kotlin, 'notice': fixture_marker,
            'prepareCommand': [sys.executable, str(destination / 'tools/prepare-synthetic.py')],
            'gradleMustRunViaParent': True, 'readme': str(destination / 'README.md')}


class BuildConventions(unittest.TestCase):
    def test_versions_and_nonproduct_boundary(self):
        settings, root = text(ROOT / "settings.gradle"), text(ROOT / "build.gradle")
        require(settings, r"gradle\.ext\.kotlinVersion = '2\.2\.10'")
        require(root, r"id 'com\.android\.application' version '9\.1\.1' apply false",
                r'classpath "org\.jetbrains\.kotlin:kotlin-gradle-plugin:\$\{gradle\.ext\.kotlinVersion\}"')
        self.assertNotRegex(settings, r"(?m)^\s*includeBuild\b")
        self.assertNotRegex(settings, r"(?m)^\s*include\b[^\n]*build-logic")
        self.assertFalse((LOGIC / "settings.gradle").exists())
        self.assertFalse((LOGIC / "build.gradle").exists())
        for path in [ROOT / "app/build.gradle", *LOGIC.glob("*.gradle")]:
            self.assertNotRegex(text(path), r"(?m)^\s*(?:id|apply plugin:)\s*['\"](?:org\.jetbrains\.kotlin\.android|kotlin-android)['\"]")
            self.assertNotRegex(text(path), r"(?m)^\s*(?:implementation|api)\s+['\"]org\.jetbrains\.kotlin:kotlin-stdlib")

    def test_existing_app_applies_real_shared_configuration(self):
        app = text(ROOT / "app/build.gradle")
        require(app, r"apply from: rootProject\.file\('build-logic/android\.gradle'\)")
        android_contract(text(LOGIC / "android.gradle"))
        test_contract(text(LOGIC / "test.gradle"))
        self.assertNotIn("testOptions {", app)
        self.assertNotIn("tasks.withType(Test)", app)
        self.assertNotIn("testImplementation 'junit:", app)

    def test_app_identity_resources_and_asset_roots_remain_local(self):
        app = text(ROOT / "app/build.gradle")
        require(app, r"(?m)^\s*versionCode [0-9]+$", r'(?m)^\s*versionName "[^"]+"$')
        for fragment in (
            "applicationId releaseIdentity.packageName", "flavorDimensions 'androidSupport'",
            "minSdk 30", "targetSdk 37", "version = release(37)", "ndkVersion '26.3.11579264'",
            "noCompress += ['gz', 'xz', 'bin', 'ja', 'tar', 'whl']",
            'main.assets.setSrcDirs([layout.buildDirectory.dir("generated/standardAssets").get().asFile,',
            'layout.buildDirectory.dir("generated/recoveryAssets").get().asFile])',
            'main.java.srcDir(layout.buildDirectory.dir("generated/uiLanguage").get().asFile)',
            'main.jniLibs.srcDir(layout.buildDirectory.dir("rust-jniLibs").get().asFile)',
            'debug.assets.srcDir(rootProject.file("tools/fixtures/web-state"))',
            'androidTest.assets.srcDir(rootProject.file("tools/fixtures/web-state"))',
            '"--source", file("src/main/assets").absolutePath',
            '"--output", layout.buildDirectory.dir("generated/standardAssets").get().asFile.absolutePath',
            '"--shared-assets", layout.buildDirectory.dir("generated/standardAssets").get().asFile.absolutePath',
        ):
            self.assertIn(fragment, app, fragment)
        android = text(LOGIC / "android.gradle")
        for local_setting in ("applicationId", "signingConfigs", "sourceSets", "packagingOptions", "noCompress"):
            self.assertNotIn(local_setting, android)

    def test_library_and_jvm_interfaces_apply_existing_plugins(self):
        library = text(LOGIC / "android-library.gradle")
        android_library_contract(library)
        java = text(LOGIC / "jvm-library.gradle")
        require(java, r"apply plugin: 'java-library'", r"languageVersion = JavaLanguageVersion\.of\(17\)",
                r"sourceCompatibility = JavaVersion\.VERSION_17", r"targetCompatibility = JavaVersion\.VERSION_17",
                r"apply from: rootProject\.file\('build-logic/test\.gradle'\)")
        kotlin = text(LOGIC / "kotlin-jvm-library.gradle")
        require(kotlin, r"gradle\.ext\.kotlinVersion != '2\.2\.10'", r"apply plugin: 'org\.jetbrains\.kotlin\.jvm'",
                r"apply from: rootProject\.file\('build-logic/jvm-library\.gradle'\)", r"jvmToolchain\(17\)",
                r"apply from: rootProject\.file\('build-logic/kotlin-target\.gradle'\)")
        kotlin_target_contract(text(LOGIC / "kotlin-target.gradle"))
        for path in LOGIC.glob("*.gradle"):
            self.assertNotRegex(text(path), r"(?m)^\s*(?:buildscript|repositories|plugins)\s*\{")

    def test_library_sdk_defaults_are_audited_and_not_supplied_by_fixture(self):
        library = text(LOGIC / "android-library.gradle")
        android_library_contract(library)
        audit = text(LOGIC / "verify.gradle")
        require(audit, r"android\.compileSdk != 37", r"android\.compileSdkPreview != null",
                r"android\.defaultConfig\.minSdk != 30", r"android\.defaultConfig\.minSdkPreview != null")
        for mutation in (library.replace("release(37)", "release(36)"), library.replace("minSdk 30", "minSdk 29")):
            with self.assertRaisesRegex(ValueError, "BUILD_CONVENTION_MISSING"):
                android_library_contract(mutation)
        with tempfile.TemporaryDirectory(prefix="dsha SDK defaults ") as folder:
            target = Path(folder) / "fixtures"
            emit_gradle_fixtures(target)
            fixture = text(target / "android/build.gradle")
            self.assertNotRegex(fixture, r"compileSdk|minSdk")
            self.assertIn("namespace 'fixture.android'", fixture)

    def test_negative_controls_reject_lost_resources_fork_memory_and_inputs(self):
        android, tests = text(LOGIC / "android.gradle"), text(LOGIC / "test.gradle")
        target = text(LOGIC / "kotlin-target.gradle")
        mutations = [(android_contract, android.replace("unitTests.includeAndroidResources = true", "")),
                     (kotlin_target_contract, target.replace("'JVM_17'", "'JVM_11'")),
                     (test_contract, tests.replace("maxParallelForks = 1", "maxParallelForks = 2")),
                     (test_contract, tests.replace("'2048m'", "'512m'")),
                     (test_contract, tests.replace("testTask.inputs.property 'dsha.sourceOnlyTests', sourceOnlyTests", "")),
                     (test_contract, tests.replace("testTask.systemProperty 'dsha.rust.required', 'true'", "")),
                     (test_contract, tests.replace("testTask.systemProperty 'robolectric.offline', 'true'", ""))]
        for check, mutation in mutations:
            with self.subTest(check=check.__name__, source=mutation[-120:]):
                with self.assertRaisesRegex(ValueError, "BUILD_CONVENTION_MISSING"):
                    check(mutation)

    def test_full_contract_uses_actual_task_and_jvm_settings(self):
        hooks = text(LOGIC / "unit-test-contract.gradle")
        require(hooks, r"System\.getenv\('DSHA_UNIT_TEST_STATE'\)", r"testTask\.doFirst", r"testTask\.doLast",
                r"'task-begin'", r"'task-end'", r"'--task', testTask\.path", r"'--outcome', 'SUCCESS'",
                r"testTask\.systemProperties\['dsha\.sourceOnlyTests'\]",
                r"testTask\.systemProperties\['dsha\.rust\.required'\]",
                r"testTask\.systemProperties\['dsha\.rust\.hostLib'\]",
                r"testTask\.filter\.includePatterns", r"testTask\.filter\.commandLineIncludePatterns",
                r"testTask\.filter\.excludePatterns", r"assertNormalExitValue\(\)",
                r"'--includes-json'", r"'--excludes-json'", r"'--scan-for-test-classes'",
                r"'--test-classes-dirs-json'", r"testTask\.testClassesDirs\.files",
                r"testTask\.dependsOn\(rootProject\.tasks\.named\('verifyDshaBuildConventions'\)\)")
        root = text(ROOT / "build.gradle")
        require(root, r"apply from: rootProject\.file\('build-logic/unit-test-contract\.gradle'\)",
                r"apply from: rootProject\.file\('build-logic/verify\.gradle'\)")
        audit = text(LOGIC / "verify.gradle")
        for actual_property in ("test.maxHeapSize", "test.maxParallelForks", "test.inputs.properties", "test.systemProperties",
                                "android.testOptions.unitTests.includeAndroidResources", "compilerOptions.jvmTarget"):
            self.assertIn(actual_property, audit)
        require(audit, r"products\.collect \{ it\.path \} as Set\) != declaredPaths",
                r"expectedTest instanceof Test", r"expectedTest\.reports\.junitXml\.outputLocation",
                r"fixture\.projectDir\.canonicalFile\.toPath\(\)\.startsWith\(rootDirectory\)",
                r"gradle\.taskGraph\.allTasks\.findAll \{ it instanceof Test \}",
                r"actualTasks != expectedTasks")

    def test_full_run_binds_actual_junit4_options_and_categories(self):
        hooks, audit = text(LOGIC / 'unit-test-contract.gradle'), text(LOGIC / 'verify.gradle')
        junit_options_contract(hooks, audit)
        self.assertLess(hooks.index('def actualJUnitOptions = testTask.options'), hooks.index("'task-begin'"))
        self.assertLess(audit.index('def actualJUnitOptions = test.options'), audit.index('test.maxHeapSize'))

    def test_junit_categories_and_framework_mutations_are_rejected(self):
        hooks, audit = text(LOGIC / 'unit-test-contract.gradle'), text(LOGIC / 'verify.gradle')
        mutations = (
            (hooks.replace("+ actualJUnitOptions.includeCategories.collect { 'junit4-include-category:' + it }", '', 1), audit),
            (hooks.replace("+ actualJUnitOptions.excludeCategories.collect { 'junit4-exclude-category:' + it }", '', 1), audit),
            (hooks.replace('org.gradle.api.tasks.testing.junit.JUnitOptions',
                           'org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions', 1), audit),
            (hooks.replace('JsonOutput.toJson(actualFilters)', 'JsonOutput.toJson([])', 1), audit),
            (hooks, audit.replace('org.gradle.api.tasks.testing.junit.JUnitOptions',
                                  'org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions', 1)),
            (hooks, audit.replace('!actualJUnitOptions.includeCategories.isEmpty() || ', '', 1)),
            (hooks, audit.replace('actualJUnitOptions.excludeCategories', 'actualJUnitOptions.includeCategories', 1)),
            (hooks, audit.replace(' || !actualJUnitOptions.excludeCategories.isEmpty()',
                                  ' && !actualJUnitOptions.excludeCategories.isEmpty()', 1)),
        )
        for number, (mutated_hooks, mutated_audit) in enumerate(mutations):
            with self.subTest(mutation=number):
                self.assertNotEqual((hooks, audit), (mutated_hooks, mutated_audit))
                with self.assertRaisesRegex(ValueError, 'BUILD_CONVENTION_MISSING'):
                    junit_options_contract(mutated_hooks, mutated_audit)

    def test_negative_init_emitter_targets_actual_options_and_never_overwrites(self):
        with tempfile.TemporaryDirectory(prefix='dsha options # 中文 ') as folder:
            for case, method, failure in (
                ('exclude-category', 'excludeCategories', 'DSHA_TEST_CATEGORIES'),
                ('include-category', 'includeCategories', 'DSHA_TEST_CATEGORIES'),
                ('junit-platform', 'useJUnitPlatform', 'DSHA_TEST_FRAMEWORK'),
            ):
                path = Path(folder) / (case + '.init.gradle')
                result = emit_test_options_negative_init(path, case, ':conventionJava:test')
                body = text(path)
                self.assertEqual(failure, result['expectedFailure'])
                self.assertIn('gradle.projectsEvaluated', body)
                self.assertIn("testTask.path == ':conventionJava:test'", body)
                self.assertIn('NEGATIVE_INIT_WRONG_ROOT', body)
                self.assertIn('NEGATIVE_INIT_TASK_MISSING', body)
                self.assertIn(method, body)
                self.assertEqual('verifyDshaBuildConventions', result['command'][-1])
                self.assertIn(str(path), result['command'])
                if case != 'junit-platform':
                    self.assertIn("junitOptions." + method + "('fixture.HiddenCategory')", body)
                with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
                    emit_test_options_negative_init(path, case)
                self.assertEqual(body, text(path))
            for case, task, error in (
                ('not-a-case', ':app:testStandardDebugUnitTest', 'NEGATIVE_INIT_CASE'),
                ('exclude-category', ':app:test --tests hidden', 'NEGATIVE_INIT_TASK'),
            ):
                path = Path(folder) / 'invalid.init.gradle'
                with self.assertRaisesRegex(ValueError, error):
                    emit_test_options_negative_init(path, case, task)
                self.assertFalse(path.exists())
        with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
            emit_test_options_negative_init(ROOT / 'build-logic/unwanted-negative.init.gradle')

    def test_negative_init_cli_only_generates_file_and_returns_expected_failure(self):
        with tempfile.TemporaryDirectory(prefix='dsha options CLI ') as folder:
            path = Path(folder) / 'exclude.init.gradle'
            command = [sys.executable, str(Path(__file__).resolve()), '--emit-test-options-negative-init', str(path)]
            completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual('exclude-category', result['case'])
            self.assertEqual(':app:testStandardDebugUnitTest', result['task'])
            self.assertEqual('DSHA_TEST_CATEGORIES', result['expectedFailure'])
            self.assertTrue(path.is_file())
            self.assertNotIn('BUILD SUCCESSFUL', completed.stdout)

    def test_actual_android_and_jvm_resource_roots_are_standard(self):
        audit = text(LOGIC / 'verify.gradle')
        resource_roots_contract(audit)
        helper = audit.split('def auditResourceRoots =', 1)[1].split('def checked =', 1)[0]
        for unrelated in ('assets', 'jniLibs', 'java.srcDirs', 'generated/standardAssets', 'generated/uiLanguage'):
            self.assertNotIn(unrelated, helper)
        self.assertNotIn('setSrcDirs', helper)

    def test_resource_source_root_mutations_are_rejected(self):
        audit = text(LOGIC / 'verify.gradle')
        mutations = (
            audit.replace("auditResourceRoots(module, sourceSet.name, 'resources', sourceSet.resources.srcDirs)", '', 1),
            audit.replace("auditResourceRoots(module, sourceSet.name, 'res', sourceSet.res.srcDirs)", '', 1),
            audit.replace("module.extensions.getByName('android').sourceSets", '[]', 1),
            audit.replace("module.extensions.getByName('sourceSets')", '[]', 1),
            audit.replace('|| resourceRoot.canonicalFile.toPath() != canonicalStandardRoot',
                          '&& resourceRoot.canonicalFile.toPath() != canonicalStandardRoot', 1),
            audit.replace('resourceRoot.absoluteFile.toPath().normalize() != declaredStandardRoot',
                          'resourceRoot.absoluteFile.toPath().normalize() == declaredStandardRoot', 1),
            audit.replace('src/${sourceSetName}/${resourceKind}', 'src/main/${resourceKind}', 1),
        )
        for number, mutation in enumerate(mutations):
            with self.subTest(mutation=number):
                self.assertNotEqual(audit, mutation)
                with self.assertRaisesRegex(ValueError, 'BUILD_CONVENTION_MISSING'):
                    resource_roots_contract(mutation)

    def test_external_resources_init_cli_targets_real_android_or_jvm_source_set(self):
        with tempfile.TemporaryDirectory(prefix='dsha external resources ') as folder:
            path = Path(folder) / 'external.init.gradle'
            completed = subprocess.run([
                sys.executable, str(Path(__file__).resolve()), '--emit-test-options-negative-init', str(path),
                '--case', 'external-resources', '--task', ':conventionJava:test',
            ], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual('external-resources', result['case'])
            self.assertEqual('DSHA_RESOURCE_SOURCE_ROOT', result['expectedFailure'])
            self.assertEqual('verifyDshaBuildConventions', result['command'][-1])
            body = text(path)
            self.assertIn('def resourceProject = testTask.project', body)
            self.assertIn("resourceProject.extensions.findByName('android')", body)
            self.assertIn("android != null ? android.sourceSets : resourceProject.extensions.getByName('sourceSets')", body)
            self.assertIn("sourceSets.getByName('test').resources.srcDir('/tmp/dsha-external-resources-fixture')", body)
            self.assertNotIn('.assets', body)
            self.assertNotIn('.java', body)
            with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
                emit_test_options_negative_init(path, 'external-resources')
            self.assertEqual(body, text(path))

    def test_source_model_emitter_outputs_only_plain_data_without_compilation(self):
        with tempfile.TemporaryDirectory(prefix='dsha source model ') as folder:
            init, output = Path(folder) / 'model.init.gradle', Path(folder) / 'model.json'
            result = emit_source_model_init(init, output)
            body = text(init)
            self.assertIn('task.source', body)
            self.assertIn('task.sources', body)
            self.assertIn('task.javaSources', body)
            self.assertIn('sourceSet.java.srcDirs', body)
            self.assertIn('sourceSet.kotlin.srcDirs', body)
            self.assertIn('plain(value.orNull)', body)
            self.assertIn('JsonOutput.toJson(model)', body)
            self.assertEqual('dumpDshaSourceModel', result['command'][-1])
            self.assertFalse(output.exists())
            with self.assertRaisesRegex(ValueError, 'NEW_FILES_OUTSIDE'):
                emit_source_model_init(init, output)

    def test_source_negative_emitters_cover_roots_compile_replacement_and_filtering(self):
        with tempfile.TemporaryDirectory(prefix='dsha source negative ') as folder:
            for language in ('java', 'kotlin'):
                for operation in ('root-replace', 'root-extra', 'compile-replace', 'compile-filter'):
                    case = language + '-' + operation
                    path = Path(folder) / (case + '.init.gradle')
                    result = emit_source_negative_init(path, case)
                    body = text(path)
                    self.assertEqual(case, result['case'])
                    self.assertEqual('DSHA_SOURCE_INPUT', result['expectedFailure'])
                    self.assertIn("gradle.rootProject.project(':app')", body)
                    self.assertTrue(Path(result['source']).is_file())
                    self.assertEqual('verifyDshaBuildConventions', result['command'][-1])
                    if operation == 'root-replace':
                        self.assertIn('selectedRoots.setSrcDirs', body)
                    elif operation == 'root-extra':
                        self.assertIn('selectedRoots.srcDir', body)
                    elif operation == 'compile-replace':
                        self.assertIn('selectedCompile.setSource', body)
                    else:
                        self.assertIn('selectedCompile.exclude', body)
                    with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
                        emit_source_negative_init(path, case)
            invalid = Path(folder) / 'invalid.init.gradle'
            with self.assertRaisesRegex(ValueError, 'SOURCE_NEGATIVE_COMPILE_TASK'):
                emit_source_negative_init(invalid, compile_task=':app:compileJava;bad')
            self.assertFalse(invalid.exists())
            self.assertFalse((Path(folder) / 'invalid.init-sources').exists())

    def test_real_compile_model_and_test_proof_are_wired_to_layout(self):
        source_inputs_contract(text(LOGIC / 'source-inputs.gradle'), text(LOGIC / 'verify.gradle'),
                               text(LOGIC / 'unit-test-contract.gradle'), text(ROOT / 'build.gradle'))
        script = text(LOGIC / 'source-inputs.gradle')
        self.assertNotIn("startsWith(module.buildDir", script)
        self.assertNotIn("include '**/*'", script)
        self.assertIn('origin: generatedFiles.contains(file)', script)
        self.assertIn("producer.namespace.get().replace('.', '/') + '/BuildConfig.java'", script)
        self.assertIn("from ui_language_catalog import LANGUAGES", script)

    def test_source_compile_guard_mutations_do_not_silently_allow_replacement(self):
        source, verify = text(LOGIC / 'source-inputs.gradle'), text(LOGIC / 'verify.gradle')
        hook, root = text(LOGIC / 'unit-test-contract.gradle'), text(ROOT / 'build.gradle')
        mutations = (
            (source.replace('actualPaths != expectedPaths', 'false', 1), verify, hook, root),
            (source.replace('actualSources != existingExpected', 'false', 1), verify, hook, root),
            (source.replace('kotlin && actualJava != existingJava', 'false', 1), verify, hook, root),
            (source.replace('if (strict || !specs.any', 'if (false && !specs.any', 1), verify, hook, root),
            (source.replace('spec.files.containsAll(actual)', 'true', 1), verify, hook, root),
            (source.replace('auditCompile(task, inventory, true)', 'auditCompile(task, inventory, false)', 1), verify, hook, root),
            (source.replace('auditCompile(task, snapshot(), true)', 'auditCompile(task, snapshot(), false)', 1), verify, hook, root),
            (source.replace('receipt.before != row || receipt.after != row', 'false', 1), verify, hook, root),
            (source.replace('start.before != after', 'false', 1), verify, hook, root),
            (source.replace("'--verify-compile-head'", "'--compile-inputs'", 1), verify, hook, root),
            (source, verify.replace('rootProject.ext.dshaAuditSourceInputs(false)', '', 1), hook, root),
            (source, verify, hook.replace("'--compile-proof', compileProof.absolutePath", '', 1), root),
        )
        for number, values in enumerate(mutations):
            with self.subTest(mutation=number):
                self.assertNotEqual((source, verify, hook, root), values)
                with self.assertRaisesRegex(ValueError, 'BUILD_CONVENTION_MISSING'):
                    source_inputs_contract(*values)

    def test_full_contract_fixture_has_explicit_synthetic_sources_and_real_scripts(self):
        with tempfile.TemporaryDirectory(prefix='dsha full contract fixture ') as folder:
            target = Path(folder) / 'project'
            result = emit_full_contract_fixture(target)
            self.assertTrue(result['syntheticOnly'])
            self.assertEqual('jvm', json.loads(text(target / 'ci/source-layout.json'))['modules'][0]['kind'])
            self.assertIn('NO_JNI_NO_PRODUCTION_CI', text(target / 'SYNTHETIC-ONLY.txt'))
            self.assertEqual(text(LOGIC / 'source-inputs.gradle'), text(target / 'build-logic/source-inputs.gradle'))
            self.assertEqual(text(ROOT / 'tools/unit_test_contract.py'), text(target / 'tools/unit_test_contract.py'))
            source = text(target / 'app/src/test/java/com/deepseekharness/app/util/RustDigestDifferentialTest.java')
            self.assertNotIn('System.load', source)
            self.assertNotIn('native ', source)
            self.assertIn('OpaqueBytesDoNotInvokeJni', source)
            self.assertIn('exec bash ' + shlex.quote(str(ROOT / 'gradlew')), text(target / 'build.sh'))
            self.assertFalse((target / '.git').exists())
            with self.assertRaisesRegex(ValueError, 'NEW_DIRECTORY_OUTSIDE'):
                emit_full_contract_fixture(target)

    def test_full_contract_fixture_prepares_head_plan_and_begin_without_gradle(self):
        with tempfile.TemporaryDirectory(prefix='dsha prepare full fixture ') as folder:
            target = Path(folder) / 'project'
            emit_full_contract_fixture(target)
            completed = subprocess.run([sys.executable, 'tools/prepare-synthetic.py'], cwd=target,
                                       capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual(8, result['tests'])
            self.assertTrue(result['syntheticOnly'])
            run = json.loads(text(target / 'out/unit-run/run.json'))
            baseline = json.loads(text(target / 'out/synthetic/baseline.json'))
            self.assertEqual(result['commit'], run['commit'])
            self.assertTrue(baseline['syntheticOnly'])
            self.assertTrue(baseline['provenance']['syntheticOnly'])
            self.assertNotIn('BUILD SUCCESSFUL', completed.stdout)
            replay = subprocess.run([sys.executable, 'tools/prepare-synthetic.py'], cwd=target,
                                    capture_output=True, text=True)
            self.assertNotEqual(0, replay.returncode)
            self.assertIn('ALREADY_PREPARED', replay.stderr)

    def test_full_kotlin_fixture_pins_toolchain_preserves_java_and_plans_nine_cases(self):
        with tempfile.TemporaryDirectory(prefix='dsha Kotlin full fixture ') as folder:
            java_root, kotlin_root = Path(folder) / 'java', Path(folder) / 'kotlin'
            emit_full_contract_fixture(java_root)
            result = emit_full_contract_fixture(kotlin_root, with_kotlin=True)
            self.assertTrue(result['withKotlin'])
            for java in (java_root / 'app/src').rglob('*.java'):
                self.assertEqual(java.read_bytes(), (kotlin_root / java.relative_to(java_root)).read_bytes())
            build = text(kotlin_root / 'build.gradle')
            self.assertIn('classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${gradle.ext.kotlinVersion}"', build)
            self.assertIn("classpath 'com.android.tools.build:gradle:9.1.1'", build)
            self.assertNotRegex(build, r"apply plugin: ['\"]com\.android\.")
            self.assertNotIn('resolutionStrategy', build)
            self.assertNotIn('force(', build)
            self.assertIn("gradle.ext.kotlinVersion = '2.2.10'", text(kotlin_root / 'settings.gradle'))
            self.assertIn('kotlin-jvm-library.gradle', text(kotlin_root / 'app/build.gradle'))
            for name in ('kotlin-jvm-library.gradle', 'kotlin-target.gradle'):
                self.assertEqual(text(LOGIC / name), text(kotlin_root / 'build-logic' / name))
            self.assertEqual((ROOT / 'gradle/verification-metadata.xml').read_bytes(),
                             (kotlin_root / 'gradle/verification-metadata.xml').read_bytes())
            production = kotlin_root / 'app/src/main/kotlin/com/deepseekharness/app/util/SyntheticKotlinValue.kt'
            self.assertIn('SyntheticValue.answer()', text(production))
            completed = subprocess.run([sys.executable, 'tools/prepare-synthetic.py'], cwd=kotlin_root,
                                       capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(9, json.loads(completed.stdout)['tests'])
            baseline = json.loads(text(kotlin_root / 'out/synthetic/baseline.json'))
            self.assertTrue(baseline['syntheticOnly'])
            self.assertIn(['com.deepseekharness.app.util.SyntheticKotlinTest',
                           'syntheticKotlinSharesJavaAndPinnedToolchain'], baseline['identities'])
            self.assertIn('九个', text(kotlin_root / 'README.md'))

    def test_kotlin_full_fixture_cli_is_scoped_to_its_emitter(self):
        with tempfile.TemporaryDirectory(prefix='dsha Kotlin emitter CLI ') as folder:
            target = Path(folder) / 'project'
            script = str(Path(__file__).resolve())
            completed = subprocess.run([sys.executable, script, '--emit-full-contract-fixture', str(target),
                                        '--with-kotlin'], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertTrue(json.loads(completed.stdout)['withKotlin'])
            self.assertFalse((target / '.git').exists())
            invalid = Path(folder) / 'invalid'
            completed = subprocess.run([sys.executable, script, '--emit-gradle-fixtures', str(invalid),
                                        '--with-kotlin'], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(2, completed.returncode)
            self.assertIn('--with-kotlin 只用于', completed.stderr)
            self.assertFalse(invalid.exists())

    def test_ci_uses_layout_tasks_all_reports_and_frozen_full_contract(self):
        workflow = text(WORKFLOW)
        for fragment in ("tools/source_layout.py --test-tasks", "tools/unit_test_contract.py plan",
                         "mapfile -t unit_tasks < out/unit-tasks.txt", 'for task in "${unit_tasks[@]}"',
                         'args+=("$task")', 'args+=(--tests "$pattern")', "args+=(--rerun-tasks)",
                         "tools/unit_test_contract.py begin", "--rust-receipt out/rust-host/receipt.json",
                         "tools/unit_test_contract.py verify", "--baseline ci/unit-test-baseline.json",
                         "inputs.mode == 'full' && inputs.tests == ''", "inputs.mode != 'full' || inputs.tests != ''",
                         "completeFixedInputRun=False", "**/build/test-results/**", "out/unit-run"):
            self.assertIn(fragment, workflow)
        self.assertNotIn("--bootstrap", workflow)
        self.assertNotIn("args=(:app:", workflow)
        self.assertNotIn("app/build/test-results/testStandardDebugUnitTest", workflow)
        self.assertLess(workflow.index('args+=("$task")'), workflow.index('args+=(--tests "$pattern")'))

    def test_workflow_shell_and_embedded_python_parse_without_execution(self):
        blocks = shell_blocks(text(WORKFLOW))
        self.assertGreaterEqual(len(blocks), 7)
        python_blocks = 0
        for block in blocks:
            result = subprocess.run(["bash", "-n"], input=block, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            for body in re.findall(r"python3 - <<'PY'\n([\s\S]*?)\nPY\n", block):
                compile(body, str(WORKFLOW), "exec")
                python_blocks += 1
        self.assertEqual(2, python_blocks)

    def test_emitted_fixtures_are_real_outside_products_and_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix="dsha conventions # 中文 ") as folder:
            target = Path(folder) / "fixtures"
            result = emit_gradle_fixtures(target)
            self.assertEqual(4, len(result["tasks"]))
            init = text(target / "include.init.gradle")
            self.assertIn(groovy_string(target / "android"), init)
            self.assertIn("settings.rootDir.canonicalPath", init)
            self.assertIn("kotlin-jvm-library.gradle", text(target / "kotlin/build.gradle"))
            self.assertIn("KotlinVersion.CURRENT", text(target / "kotlin/src/test/kotlin/fixture/KotlinConventionTest.kt"))
            self.assertIn("R.string.fixture_value", text(target / "android/src/test/java/fixture/android/AndroidConventionTest.java"))
            self.assertTrue((target / "java/src/test/java/fixture/JavaConventionTest.java").is_file())
            with self.assertRaisesRegex(ValueError, "NEW_DIRECTORY_OUTSIDE"):
                emit_gradle_fixtures(target)
        with self.assertRaisesRegex(ValueError, "NEW_DIRECTORY_OUTSIDE"):
            emit_gradle_fixtures(ROOT / "build-logic/unwanted-product")


if __name__ == "__main__":
    if sys.flags.optimize:
        raise SystemExit("BUILD_CONVENTIONS_OPTIMIZATION_FORBIDDEN")
    if any(argument.split('=', 1)[0] in ('--emit-gradle-fixtures', '--emit-test-options-negative-init',
                                         '--emit-source-model-init', '--emit-source-negative-init', '--emit-full-contract-fixture')
           for argument in sys.argv[1:]):
        parser = argparse.ArgumentParser(description=__doc__)
        modes = parser.add_mutually_exclusive_group(required=True)
        modes.add_argument('--emit-gradle-fixtures', type=Path)
        modes.add_argument('--emit-test-options-negative-init', type=Path)
        modes.add_argument('--emit-source-model-init', type=Path)
        modes.add_argument('--emit-source-negative-init', type=Path)
        modes.add_argument('--emit-full-contract-fixture', type=Path)
        parser.add_argument('--with-kotlin', action='store_true', help='为合成 full 夹具启用固定 KGP 2.2.10 与第九个 Kotlin 用例')
        parser.add_argument('--model-output', type=Path)
        parser.add_argument('--source-case', choices=('java-root-replace', 'java-root-extra', 'kotlin-root-replace',
                            'kotlin-root-extra', 'java-compile-replace', 'java-compile-filter',
                            'kotlin-compile-replace', 'kotlin-compile-filter'), default='java-root-replace')
        parser.add_argument('--module', default=':app')
        parser.add_argument('--compile-task')
        parser.add_argument('--case', choices=('exclude-category', 'include-category', 'junit-platform', 'external-resources'),
                            default='exclude-category', help='真实 Test.options / 资源源根负控，默认 exclude-category')
        parser.add_argument('--task', default=':app:testStandardDebugUnitTest', help='负控目标 Test 的完整任务路径')
        args = parser.parse_args()
        if args.with_kotlin and not args.emit_full_contract_fixture:
            parser.error('--with-kotlin 只用于 --emit-full-contract-fixture')
        if args.emit_full_contract_fixture:
            result = emit_full_contract_fixture(args.emit_full_contract_fixture, args.with_kotlin)
        elif args.emit_source_model_init:
            if args.model_output is None:
                parser.error('--emit-source-model-init requires --model-output')
            result = emit_source_model_init(args.emit_source_model_init, args.model_output)
        elif args.emit_source_negative_init:
            result = emit_source_negative_init(args.emit_source_negative_init, args.source_case,
                                               args.module, args.compile_task)
        elif args.emit_test_options_negative_init:
            result = emit_test_options_negative_init(args.emit_test_options_negative_init, args.case, args.task)
        else:
            if any(argument.split('=', 1)[0] in ('--case', '--task') for argument in sys.argv[1:]):
                parser.error('--case / --task 只用于 --emit-test-options-negative-init')
            result = emit_gradle_fixtures(args.emit_gradle_fixtures)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        unittest.main(verbosity=2)
