/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analyzer.AbstractAnalyzerWithCompilerReport
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContextImpl
import org.jetbrains.kotlin.backend.common.serialization.DeserializationStrategy
import org.jetbrains.kotlin.backend.common.serialization.KlibIrVersion
import org.jetbrains.kotlin.backend.common.serialization.knownBuiltins
import org.jetbrains.kotlin.backend.common.serialization.mangle.ManglerChecker
import org.jetbrains.kotlin.backend.common.serialization.mangle.descriptor.Ir2DescriptorManglerAdapter
import org.jetbrains.kotlin.backend.common.serialization.metadata.DynamicTypeDeserializer
import org.jetbrains.kotlin.backend.common.serialization.metadata.KlibMetadataVersion
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureDescriptor
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrModuleSerializer
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerDesc
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerIr
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.KlibMetadataIncrementalSerializer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.IrFunctionFactory
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.analyzer.JsAnalysisResult
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.konan.properties.propertyList
import org.jetbrains.kotlin.konan.util.KlibMetadataFactories
import org.jetbrains.kotlin.library.*
import org.jetbrains.kotlin.library.impl.BuiltInsPlatform
import org.jetbrains.kotlin.library.impl.buildKotlinLibrary
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.library.resolver.TopologicalLibraryOrder
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.progress.IncrementalNextRoundException
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.generators.GeneratorExtensions
import org.jetbrains.kotlin.psi2ir.generators.createGeneratorContext
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.utils.DFS
import java.io.File
import org.jetbrains.kotlin.konan.file.File as KFile

val KotlinLibrary.moduleName: String
    get() = manifestProperties.getProperty(KLIB_PROPERTY_UNIQUE_NAME)

// Considering library built-ins if it has no dependencies.
// All non-built-ins libraries must have built-ins as a dependency.
val KotlinLibrary.isBuiltIns: Boolean
    get() = manifestProperties
        .propertyList(KLIB_PROPERTY_DEPENDS, escapeInQuotes = true)
        .isEmpty()

// TODO: The only place loadKlib() is used now is Wasm backend.
// Need to move it to SearchPathResolver too.
fun loadKlib(klibPath: String) =
    resolveSingleFileKlib(KFile(KFile(klibPath).absolutePath))

val emptyLoggingContext = object : LoggingContext {
    override var inVerbosePhase = false

    override fun log(message: () -> String) {}
}

private val CompilerConfiguration.metadataVersion
    get() = get(CommonConfigurationKeys.METADATA_VERSION) as? KlibMetadataVersion ?: KlibMetadataVersion.INSTANCE

private val CompilerConfiguration.expectActualLinker: Boolean
    get() = get(CommonConfigurationKeys.EXPECT_ACTUAL_LINKER) ?: false

class KotlinFileSerializedData(val metadata: ByteArray, val irData: SerializedIrFile)

fun generateKLib(
    project: Project,
    files: List<KtFile>,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    allDependencies: KotlinLibraryResolveResult,
    friendDependencies: List<KotlinLibrary>,
    outputKlibPath: String,
    nopack: Boolean
) {
    val incrementalDataProvider = configuration.get(JSConfigurationKeys.INCREMENTAL_DATA_PROVIDER)

    val icData: List<KotlinFileSerializedData>
    val serializedIrFiles: List<SerializedIrFile>?

    if (incrementalDataProvider != null) {
        val nonCompiledSources = files.map { VfsUtilCore.virtualToIoFile(it.virtualFile) to it }.toMap()
        val compiledIrFiles = incrementalDataProvider.serializedIrFiles
        val compiledMetaFiles = incrementalDataProvider.compiledPackageParts

        assert(compiledIrFiles.size == compiledMetaFiles.size)

        val storage = mutableListOf<KotlinFileSerializedData>()

        for (f in compiledIrFiles.keys) {
            if (f in nonCompiledSources) continue

            val irData = compiledIrFiles[f] ?: error("No Ir Data found for file $f")
            val metaFile = compiledMetaFiles[f] ?: error("No Meta Data found for file $f")
            val irFile = with(irData) {
                SerializedIrFile(fileData, String(fqn), f.path.replace('\\', '/'), types, signatures, strings, bodies, declarations)
            }
            storage.add(KotlinFileSerializedData(metaFile.metadata, irFile))
        }

        icData = storage
        serializedIrFiles = storage.map { it.irData }
    } else {
        icData = emptyList()
        serializedIrFiles = null
    }

    val depsDescriptors =
        ModulesStructure(project, MainModule.SourceFiles(files), analyzer, configuration, allDependencies, friendDependencies)

    val psi2IrContext = runAnalysisAndPreparePsi2Ir(depsDescriptors)
    val irBuiltIns = psi2IrContext.irBuiltIns
    val functionFactory = IrFunctionFactory(irBuiltIns, psi2IrContext.symbolTable)
    irBuiltIns.functionFactory = functionFactory

    val expectDescriptorToSymbol = mutableMapOf<DeclarationDescriptor, IrSymbol>()

    val irLinker = JsIrLinker(
        psi2IrContext.moduleDescriptor,
        emptyLoggingContext,
        psi2IrContext.irBuiltIns,
        psi2IrContext.symbolTable,
        functionFactory,
        serializedIrFiles
    )

    val deserializedModuleFragments = sortDependencies(allDependencies.getFullList(), depsDescriptors.descriptors).map {
        irLinker.deserializeOnlyHeaderModule(depsDescriptors.getModuleDescriptor(it), it)
    }

    val moduleFragment = psi2IrContext.generateModuleFragmentWithPlugins(project, files, irLinker, expectDescriptorToSymbol)

    moduleFragment.acceptVoid(ManglerChecker(JsManglerIr, Ir2DescriptorManglerAdapter(JsManglerDesc)))

    val moduleName = configuration[CommonConfigurationKeys.MODULE_NAME]!!

    if (!configuration.expectActualLinker) {
        moduleFragment.acceptVoid(ExpectDeclarationRemover(psi2IrContext.symbolTable, false))
    }

    serializeModuleIntoKlib(
        moduleName,
        configuration,
        psi2IrContext.bindingContext,
        files,
        outputKlibPath,
        allDependencies.getFullList(),
        moduleFragment,
        expectDescriptorToSymbol,
        icData,
        nopack
    )
}

data class IrModuleInfo(
    val module: IrModuleFragment,
    val allDependencies: List<IrModuleFragment>,
    val bultins: IrBuiltIns,
    val symbolTable: SymbolTable,
    val deserializer: JsIrLinker
)

private fun sortDependencies(dependencies: List<KotlinLibrary>, mapping: Map<KotlinLibrary, ModuleDescriptor>): Collection<KotlinLibrary> {
    val m2l = mapping.map { it.value to it.key }.toMap()

    return DFS.topologicalOrder(dependencies) { m ->
        val descriptor = mapping[m] ?: error("No descriptor found for library ${m.libraryName}")
        descriptor.allDependencyModules.filter { it != descriptor }.map { m2l[it] }
    }.reversed()
}

fun loadIr(
    project: Project,
    mainModule: MainModule,
    analyzer: AbstractAnalyzerWithCompilerReport,
    configuration: CompilerConfiguration,
    allDependencies: KotlinLibraryResolveResult,
    friendDependencies: List<KotlinLibrary>
): IrModuleInfo {
    val depsDescriptors = ModulesStructure(project, mainModule, analyzer, configuration, allDependencies, friendDependencies)

    when (mainModule) {
        is MainModule.SourceFiles -> {
            val psi2IrContext: GeneratorContext = runAnalysisAndPreparePsi2Ir(depsDescriptors)
            val irBuiltIns = psi2IrContext.irBuiltIns
            val symbolTable = psi2IrContext.symbolTable
            val functionFactory = IrFunctionFactory(irBuiltIns, symbolTable)
            irBuiltIns.functionFactory = functionFactory

            val irLinker = JsIrLinker(psi2IrContext.moduleDescriptor, emptyLoggingContext, irBuiltIns, symbolTable, functionFactory, null)

            val deserializedModuleFragments = sortDependencies(allDependencies.getFullList(), depsDescriptors.descriptors).map {
                irLinker.deserializeIrModuleHeader(depsDescriptors.getModuleDescriptor(it), it)
            }

            val moduleFragment = psi2IrContext.generateModuleFragmentWithPlugins(project, mainModule.files, irLinker)

            // TODO: not sure whether this check should be enabled by default. Add configuration key for it.
            val mangleChecker = ManglerChecker(JsManglerIr, Ir2DescriptorManglerAdapter(JsManglerDesc))
            moduleFragment.acceptVoid(mangleChecker)
            irBuiltIns.knownBuiltins.forEach { it.acceptVoid(mangleChecker) }

            return IrModuleInfo(moduleFragment, deserializedModuleFragments, irBuiltIns, symbolTable, irLinker)
        }
        is MainModule.Klib -> {
            val moduleDescriptor = depsDescriptors.getModuleDescriptor(mainModule.lib)
            val mangler = JsManglerDesc
            val signaturer = IdSignatureDescriptor(mangler)
            val symbolTable = SymbolTable(signaturer)
            val constantValueGenerator = ConstantValueGenerator(moduleDescriptor, symbolTable)
            val typeTranslator = TypeTranslator(
                symbolTable,
                depsDescriptors.compilerConfiguration.languageVersionSettings,
                builtIns = moduleDescriptor.builtIns
            )
            typeTranslator.constantValueGenerator = constantValueGenerator
            constantValueGenerator.typeTranslator = typeTranslator
            val irBuiltIns = IrBuiltIns(moduleDescriptor.builtIns, typeTranslator, symbolTable)
            val functionFactory = IrFunctionFactory(irBuiltIns, symbolTable)
            val irLinker = JsIrLinker(null, emptyLoggingContext, irBuiltIns, symbolTable, functionFactory, null)

            val deserializedModuleFragments = sortDependencies(allDependencies.getFullList(), depsDescriptors.descriptors).map {
                val strategy =
                    if (it == mainModule.lib)
                        DeserializationStrategy.ALL
                    else
                        DeserializationStrategy.EXPLICITLY_EXPORTED

                irLinker.deserializeIrModuleHeader(depsDescriptors.getModuleDescriptor(it), it, strategy)
            }
            irBuiltIns.functionFactory = functionFactory

            val moduleFragment = deserializedModuleFragments.last()

            irLinker.init(null)
            ExternalDependenciesGenerator(symbolTable, listOf(irLinker), configuration.languageVersionSettings).generateUnboundSymbolsAsDependencies()
            irLinker.postProcess()

            return IrModuleInfo(moduleFragment, deserializedModuleFragments, irBuiltIns, symbolTable, irLinker)
        }
    }
}

private fun runAnalysisAndPreparePsi2Ir(depsDescriptors: ModulesStructure): GeneratorContext {
    val analysisResult = depsDescriptors.runAnalysis()
    val mangler = JsManglerDesc
    val signaturer = IdSignatureDescriptor(mangler)

    return createGeneratorContext(
        Psi2IrConfiguration(),
        analysisResult.moduleDescriptor,
        analysisResult.bindingContext,
        depsDescriptors.compilerConfiguration.languageVersionSettings,
        SymbolTable(signaturer),
        GeneratorExtensions()
    )
}

fun GeneratorContext.generateModuleFragmentWithPlugins(
    project: Project,
    files: List<KtFile>,
    irLinker: IrDeserializer,
    expectDescriptorToSymbol: MutableMap<DeclarationDescriptor, IrSymbol>? = null
): IrModuleFragment {
    val signaturer = IdSignatureDescriptor(JsManglerDesc)
    val psi2Ir = Psi2IrTranslator(languageVersionSettings, configuration, signaturer)

    val extensions = IrGenerationExtension.getInstances(project)

    for (extension in extensions) {
        psi2Ir.addPostprocessingStep { module ->
            extension.generate(
                module,
                IrPluginContextImpl(
                    moduleDescriptor,
                    bindingContext,
                    languageVersionSettings,
                    symbolTable,
                    typeTranslator,
                    irBuiltIns,
                    linker = irLinker
                )
            )
        }
    }

    return psi2Ir.generateModuleFragment(this, files, listOf(irLinker), expectDescriptorToSymbol)
}

private fun createBuiltIns(storageManager: StorageManager) = object : KotlinBuiltIns(storageManager) {}
internal val JsFactories = KlibMetadataFactories(::createBuiltIns, DynamicTypeDeserializer)

fun getModuleDescriptorByLibrary(current: KotlinLibrary, mapping: Map<String, ModuleDescriptorImpl>): ModuleDescriptorImpl {
    val md = JsFactories.DefaultDeserializedDescriptorFactory.createDescriptorOptionalBuiltIns(
        current,
        LanguageVersionSettingsImpl.DEFAULT,
        LockBasedStorageManager.NO_LOCKS,
        null,
        packageAccessHandler = null, // TODO: This is a speed optimization used by Native. Don't bother for now.
        lookupTracker = LookupTracker.DO_NOTHING
    )
//    if (isBuiltIns) runtimeModule = md

    val dependencies = current.manifestProperties.propertyList(KLIB_PROPERTY_DEPENDS, escapeInQuotes = true).map { mapping.getValue(it) }

    md.setDependencies(listOf(md) + dependencies)
    return md
}

object JsIrCompilationError : Throwable()

sealed class MainModule {
    class SourceFiles(val files: List<KtFile>) : MainModule()
    class Klib(val lib: KotlinLibrary) : MainModule()
}

private class ModulesStructure(
    private val project: Project,
    private val mainModule: MainModule,
    private val analyzer: AbstractAnalyzerWithCompilerReport,
    val compilerConfiguration: CompilerConfiguration,
    val allDependencies: KotlinLibraryResolveResult,
    private val friendDependencies: List<KotlinLibrary>
) {
    val moduleDependencies: Map<KotlinLibrary, List<KotlinLibrary>> = run {
        val result = mutableMapOf<KotlinLibrary, List<KotlinLibrary>>()

        allDependencies.forEach { klib, _ ->
            val dependencies = allDependencies.filterRoots {
                it.library == klib
            }.getFullList(TopologicalLibraryOrder)
            result.put(klib, dependencies.minus(klib))
        }
        result
    }

    val builtInsDep = allDependencies.getFullList().find { it.isBuiltIns }

    fun runAnalysis(): JsAnalysisResult {
        require(mainModule is MainModule.SourceFiles)
        val files = mainModule.files

        analyzer.analyzeAndReport(files) {
            TopDownAnalyzerFacadeForJSIR.analyzeFiles(
                files,
                project,
                compilerConfiguration,
                allDependencies.getFullList().map { getModuleDescriptor(it) },
                friendModuleDescriptors = friendDependencies.map { getModuleDescriptor(it) },
                thisIsBuiltInsModule = builtInModuleDescriptor == null,
                customBuiltInsModule = builtInModuleDescriptor
            )
        }

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        val analysisResult = analyzer.analysisResult
        if (IncrementalCompilation.isEnabledForJs()) {
            /** can throw [IncrementalNextRoundException] */
            compareMetadataAndGoToNextICRoundIfNeeded(analysisResult, compilerConfiguration, files)
        }
        if (analyzer.hasErrors() || analysisResult !is JsAnalysisResult)
            throw JsIrCompilationError

        TopDownAnalyzerFacadeForJSIR.checkForErrors(files, analysisResult.bindingContext)

        return analysisResult
    }

    private val languageVersionSettings: LanguageVersionSettings = compilerConfiguration.languageVersionSettings

    private val storageManager: LockBasedStorageManager = LockBasedStorageManager("ModulesStructure")
    private var runtimeModule: ModuleDescriptorImpl? = null

    // TODO: these are roughly equivalent to KlibResolvedModuleDescriptorsFactoryImpl. Refactor me.
    val descriptors = mutableMapOf<KotlinLibrary, ModuleDescriptorImpl>()

    fun getModuleDescriptor(current: KotlinLibrary): ModuleDescriptorImpl = descriptors.getOrPut(current) {
        val isBuiltIns = current.unresolvedDependencies.isEmpty()

        val lookupTracker = compilerConfiguration[CommonConfigurationKeys.LOOKUP_TRACKER] ?: LookupTracker.DO_NOTHING
        val md = JsFactories.DefaultDeserializedDescriptorFactory.createDescriptorOptionalBuiltIns(
            current,
            languageVersionSettings,
            storageManager,
            runtimeModule?.builtIns,
            packageAccessHandler = null, // TODO: This is a speed optimization used by Native. Don't bother for now.
            lookupTracker = lookupTracker
        )
        if (isBuiltIns) runtimeModule = md

        val dependencies = moduleDependencies.getValue(current).map { getModuleDescriptor(it) }
        md.setDependencies(listOf(md) + dependencies)
        md
    }

    val builtInModuleDescriptor =
        if (builtInsDep != null)
            getModuleDescriptor(builtInsDep)
        else
            null // null in case compiling builtInModule itself
}

private fun getDescriptorForElement(
    context: BindingContext,
    element: PsiElement
): DeclarationDescriptor = BindingContextUtils.getNotNull(context, BindingContext.DECLARATION_TO_DESCRIPTOR, element)

fun serializeModuleIntoKlib(
    moduleName: String,
    configuration: CompilerConfiguration,
    bindingContext: BindingContext,
    files: List<KtFile>,
    klibPath: String,
    dependencies: List<KotlinLibrary>,
    moduleFragment: IrModuleFragment,
    expectDescriptorToSymbol: MutableMap<DeclarationDescriptor, IrSymbol>,
    cleanFiles: List<KotlinFileSerializedData>,
    nopack: Boolean
) {
    assert(files.size == moduleFragment.files.size)

    val serializedIr =
        JsIrModuleSerializer(
            emptyLoggingContext,
            moduleFragment.irBuiltins,
            expectDescriptorToSymbol = expectDescriptorToSymbol,
            skipExpects = !configuration.expectActualLinker
        ).serializedIrModule(moduleFragment)

    val moduleDescriptor = moduleFragment.descriptor
    val metadataSerializer = KlibMetadataIncrementalSerializer(configuration)

    val incrementalResultsConsumer = configuration.get(JSConfigurationKeys.INCREMENTAL_RESULTS_CONSUMER)
    val empty = ByteArray(0)

    fun processCompiledFileData(ioFile: File, compiledFile: KotlinFileSerializedData) {
        incrementalResultsConsumer?.run {
            processPackagePart(ioFile, compiledFile.metadata, empty, empty)
            with(compiledFile.irData) {
                processIrFile(ioFile, fileData, types, signatures, strings, declarations, bodies, fqName.toByteArray())
            }
        }
    }

    val additionalFiles = mutableListOf<KotlinFileSerializedData>()

    for ((ktFile, binaryFile) in files.zip(serializedIr.files)) {
        assert(ktFile.virtualFilePath == binaryFile.path) {
            """The Kt and Ir files are put in different order
                Kt: ${ktFile.virtualFilePath}
                Ir: ${binaryFile.path}
            """.trimMargin()
        }
        val packageFragment = metadataSerializer.serializeScope(ktFile, bindingContext, moduleDescriptor)
        val compiledKotlinFile = KotlinFileSerializedData(packageFragment.toByteArray(), binaryFile)

        additionalFiles += compiledKotlinFile
        processCompiledFileData(VfsUtilCore.virtualToIoFile(ktFile.virtualFile), compiledKotlinFile)
    }

    val compiledKotlinFiles = (cleanFiles + additionalFiles)

    val header = metadataSerializer.serializeHeader(
        moduleDescriptor,
        compiledKotlinFiles.map { it.irData.fqName }.distinct().sorted(),
        emptyList()
    ).toByteArray()
    incrementalResultsConsumer?.run {
        processHeader(header)
    }

    val serializedMetadata =
        metadataSerializer.serializedMetadata(
            compiledKotlinFiles.groupBy { it.irData.fqName }.map { (fqn, data) -> fqn to data.sortedBy { it.irData.path }.map { it.metadata } }.toMap(),
            header
        )

    val fullSerializedIr = SerializedIrModule(compiledKotlinFiles.map { it.irData })

    val versions = KotlinLibraryVersioning(
        abiVersion = KotlinAbiVersion.CURRENT,
        libraryVersion = null,
        compilerVersion = KotlinCompilerVersion.VERSION,
        metadataVersion = KlibMetadataVersion.INSTANCE.toString(),
        irVersion = KlibIrVersion.INSTANCE.toString()
    )

    buildKotlinLibrary(
        linkDependencies = dependencies,
        ir = fullSerializedIr,
        metadata = serializedMetadata,
        dataFlowGraph = null,
        manifestProperties = null,
        moduleName = moduleName,
        nopack = nopack,
        output = klibPath,
        versions = versions,
        builtInsPlatform = BuiltInsPlatform.JS
    )
}

private fun KlibMetadataIncrementalSerializer.serializeScope(
    ktFile: KtFile,
    bindingContext: BindingContext,
    moduleDescriptor: ModuleDescriptor
): ProtoBuf.PackageFragment {
    val memberScope = ktFile.declarations.map { getDescriptorForElement(bindingContext, it) }
    return serializePackageFragment(moduleDescriptor, memberScope, ktFile.packageFqName)
}

private fun compareMetadataAndGoToNextICRoundIfNeeded(
    analysisResult: AnalysisResult,
    config: CompilerConfiguration,
    files: List<KtFile>
) {
    val nextRoundChecker = config.get(JSConfigurationKeys.INCREMENTAL_NEXT_ROUND_CHECKER) ?: return
    val bindingContext = analysisResult.bindingContext
    val serializer = KlibMetadataIncrementalSerializer(config)
    for (ktFile in files) {
        val packageFragment = serializer.serializeScope(ktFile, bindingContext, analysisResult.moduleDescriptor)
        // to minimize a number of IC rounds, we should inspect all proto for changes first,
        // then go to a next round if needed, with all new dirty files
        nextRoundChecker.checkProtoChanges(VfsUtilCore.virtualToIoFile(ktFile.virtualFile), packageFragment.toByteArray())
    }

    if (nextRoundChecker.shouldGoToNextRound()) throw IncrementalNextRoundException()
}

private fun KlibMetadataIncrementalSerializer(configuration: CompilerConfiguration) = KlibMetadataIncrementalSerializer(
    languageVersionSettings = configuration.languageVersionSettings,
    metadataVersion = configuration.metadataVersion,
    skipExpects = !configuration.expectActualLinker
)
