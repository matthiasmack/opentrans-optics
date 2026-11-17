package opentrans.codegen

import java.io.File

/**
 * Generator entry point.
 *
 * Arguments: <model|xml|dsl> <root xsd> <output dir> <package> [<model package>]
 * The output directory is wiped first, so stale files never survive.
 */
fun main(args: Array<String>) {
    require(args.size in 4..5) { "usage: Generate <model|xml|dsl> <root xsd> <output dir> <package> [<model package>]" }
    val (mode, xsd, out, pkg) = args
    val outDir = File(out)
    outDir.deleteRecursively()
    outDir.mkdirs()

    val ir = IrBuilder(SchemaLoader.load(File(xsd))).build()
    when (mode) {
        "model" -> ModelEmitter(pkg).emit(ir, outDir)
        "xml" -> {
            val modelPkg = args.getOrElse(4) { error("xml needs the model package") }
            ReaderEmitter(pkg, modelPkg).emit(ir, outDir)
            WriterEmitter(pkg, modelPkg).emit(ir, outDir)
        }
        "dsl" -> DslEmitter(pkg, args.getOrElse(4) { error("dsl needs the model package") }).emit(ir, outDir)
        else -> error("unknown mode $mode")
    }
    println("$mode: ${ir.classes.size} classes, ${ir.enums.size} enums, ${ir.choices.size} choices -> $outDir")
}
