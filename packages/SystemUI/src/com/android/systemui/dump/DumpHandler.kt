/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.dump

import android.content.Context
import android.os.SystemClock
import android.os.Trace
import com.android.systemui.CoreStartable
import com.android.systemui.ProtoDumpable
import com.android.systemui.R
import com.android.systemui.dump.DumpHandler.Companion.PRIORITY_ARG_CRITICAL
import com.android.systemui.dump.DumpHandler.Companion.PRIORITY_ARG_NORMAL
import com.android.systemui.dump.DumpsysEntry.DumpableEntry
import com.android.systemui.dump.DumpsysEntry.LogBufferEntry
import com.android.systemui.dump.DumpsysEntry.TableLogBufferEntry
import com.android.systemui.dump.nano.SystemUIProtoDump
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.google.protobuf.nano.MessageNano
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/**
 * Oversees SystemUI's output during bug reports (and dumpsys in general)
 *
 * Dump output is split into two sections, CRITICAL and NORMAL. In general, the CRITICAL section
 * contains all dumpables that were registered to the [DumpManager], while the NORMAL sections
 * contains all [LogBuffer]s and [TableLogBuffer]s (due to their length).
 *
 * The CRITICAL and NORMAL sections can be found within a bug report by searching for "SERVICE
 * com.android.systemui/.SystemUIService" and "SERVICE
 * com.android.systemui/.dump.SystemUIAuxiliaryDumpService", respectively.
 *
 * Finally, some or all of the dump can be triggered on-demand via adb (see below).
 *
 * ```
 * # For the following, let <invocation> be:
 * $ adb shell dumpsys activity service com.android.systemui/.SystemUIService
 *
 * # To dump specific target(s), specify one or more registered names:
 * $ <invocation> NotifCollection
 * $ <invocation> StatusBar FalsingManager BootCompleteCacheImpl
 *
 * # Log buffers can be dumped in the same way (and can even be mixed in with other dump targets,
 * # although it's not clear why one would want such a thing):
 * $ <invocation> NotifLog
 * $ <invocation> StatusBar NotifLog BootCompleteCacheImpl
 *
 * # If passing -t or --tail, shows only the last N lines of any log buffers:
 * $ <invocation> NotifLog --tail 100
 *
 * # Dump targets are matched using String.endsWith(), so dumpables that register using their
 * # fully-qualified class name can still be dumped using their short name:
 * $ <invocation> com.android.keyguard.KeyguardUpdateMonitor
 * $ <invocation> keyguard.KeyguardUpdateMonitor
 * $ <invocation> KeyguardUpdateMonitor
 *
 * # To dump all dumpables or all buffers:
 * $ <invocation> dumpables
 * $ <invocation> buffers
 * $ <invocation> tables
 *
 * # Finally, the following will simulate what we dump during the CRITICAL and NORMAL sections of a
 * # bug report:
 * $ <invocation> bugreport-critical
 * $ <invocation> bugreport-normal
 *
 * # And if you need to be reminded of this list of commands:
 * $ <invocation> -h
 * $ <invocation> --help
 * ```
 */
class DumpHandler
@Inject
constructor(
    private val context: Context,
    private val dumpManager: DumpManager,
    private val logBufferEulogizer: LogBufferEulogizer,
    private val startables: MutableMap<Class<*>, Provider<CoreStartable>>,
) {
    /** Dump the diagnostics! Behavior can be controlled via [args]. */
    fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
        Trace.beginSection("DumpManager#dump()")
        val start = SystemClock.uptimeMillis()

        val parsedArgs =
            try {
                parseArgs(args)
            } catch (e: ArgParseException) {
                pw.println(e.message)
                return
            }

        when {
            parsedArgs.dumpPriority == PRIORITY_ARG_CRITICAL -> dumpCritical(pw, parsedArgs)
            parsedArgs.dumpPriority == PRIORITY_ARG_NORMAL && !parsedArgs.proto -> {
                dumpNormal(pw, parsedArgs)
            }
            else -> dumpParameterized(fd, pw, parsedArgs)
        }

        pw.println()
        pw.println("Dump took ${SystemClock.uptimeMillis() - start}ms")
        Trace.endSection()
    }

    private fun dumpParameterized(fd: FileDescriptor, pw: PrintWriter, args: ParsedArgs) {
        when (args.command) {
            "bugreport-critical" -> dumpCritical(pw, args)
            "bugreport-normal" -> dumpNormal(pw, args)
            "dumpables" -> dumpDumpables(pw, args)
            "buffers" -> dumpBuffers(pw, args)
            "tables" -> dumpTables(pw, args)
            "config" -> dumpConfig(pw)
            "help" -> dumpHelp(pw)
            else -> {
                if (args.proto) {
                    dumpProtoTargets(args.nonFlagArgs, fd, args)
                } else {
                    dumpTargets(args.nonFlagArgs, pw, args)
                }
            }
        }
    }

    private fun dumpCritical(pw: PrintWriter, args: ParsedArgs) {
        val targets = dumpManager.getDumpables()
        for (target in targets) {
            if (target.priority == DumpPriority.CRITICAL) {
                dumpDumpable(target, pw, args.rawArgs)
            }
        }
        dumpConfig(pw)
    }

    private fun dumpNormal(pw: PrintWriter, args: ParsedArgs) {
        val targets = dumpManager.getDumpables()
        for (target in targets) {
            if (target.priority == DumpPriority.NORMAL) {
                dumpDumpable(target, pw, args.rawArgs)
            }
        }

        val buffers = dumpManager.getLogBuffers()
        for (buffer in buffers) {
            dumpBuffer(buffer, pw, args.tailLength)
        }

        val tableBuffers = dumpManager.getTableLogBuffers()
        for (table in tableBuffers) {
            dumpTableBuffer(table, pw, args.rawArgs)
        }

        logBufferEulogizer.readEulogyIfPresent(pw)
    }

    private fun dumpDumpables(pw: PrintWriter, args: ParsedArgs) =
        dumpManager.getDumpables().listOrDumpEntries(pw, args)

    private fun dumpBuffers(pw: PrintWriter, args: ParsedArgs) =
        dumpManager.getLogBuffers().listOrDumpEntries(pw, args)

    private fun dumpTables(pw: PrintWriter, args: ParsedArgs) =
        dumpManager.getTableLogBuffers().listOrDumpEntries(pw, args)

    private fun listTargetNames(targets: Collection<DumpsysEntry>, pw: PrintWriter) {
        for (target in targets) {
            pw.println(target.name)
        }
    }

    private fun dumpProtoTargets(targets: List<String>, fd: FileDescriptor, args: ParsedArgs) {
        val systemUIProto = SystemUIProtoDump()
        val dumpables = dumpManager.getDumpables()
        if (targets.isNotEmpty()) {
            for (target in targets) {
                findBestProtoTargetMatch(dumpables, target)?.dumpProto(systemUIProto, args.rawArgs)
            }
        } else {
            // Dump all protos
            for (dumpable in dumpables) {
                (dumpable.dumpable as? ProtoDumpable)?.dumpProto(systemUIProto, args.rawArgs)
            }
        }

        val buffer = BufferedOutputStream(FileOutputStream(fd))
        buffer.use {
            it.write(MessageNano.toByteArray(systemUIProto))
            it.flush()
        }
    }

    // Attempts to dump the target list to the given PrintWriter. Since the arguments come in as
    // a list of strings, we use the [findBestTargetMatch] method to determine the most-correct
    // target with the given search string.
    private fun dumpTargets(targets: List<String>, pw: PrintWriter, args: ParsedArgs) {
        if (targets.isNotEmpty()) {
            val dumpables = dumpManager.getDumpables()
            val buffers = dumpManager.getLogBuffers()
            val tableBuffers = dumpManager.getTableLogBuffers()

            targets.forEach { target ->
                findTargetInCollection(target, dumpables, buffers, tableBuffers)?.dump(pw, args)
            }
        } else {
            if (args.listOnly) {
                val dumpables = dumpManager.getDumpables()
                val buffers = dumpManager.getLogBuffers()

                pw.println("Dumpables:")
                listTargetNames(dumpables, pw)
                pw.println()

                pw.println("Buffers:")
                listTargetNames(buffers, pw)
            } else {
                pw.println("Nothing to dump :(")
            }
        }
    }

    private fun findTargetInCollection(
        target: String,
        dumpables: Collection<DumpableEntry>,
        logBuffers: Collection<LogBufferEntry>,
        tableBuffers: Collection<TableLogBufferEntry>,
    ) =
        sequence {
                findBestTargetMatch(dumpables, target)?.let { yield(it) }
                findBestTargetMatch(logBuffers, target)?.let { yield(it) }
                findBestTargetMatch(tableBuffers, target)?.let { yield(it) }
            }
            .sortedBy { it.name }
            .minByOrNull { it.name.length }

    private fun dumpDumpable(entry: DumpableEntry, pw: PrintWriter, args: Array<String>) {
        pw.preamble(entry)
        entry.dumpable.dump(pw, args)
    }

    private fun dumpBuffer(entry: LogBufferEntry, pw: PrintWriter, tailLength: Int) {
        pw.preamble(entry)
        entry.buffer.dump(pw, tailLength)
    }

    private fun dumpTableBuffer(buffer: TableLogBufferEntry, pw: PrintWriter, args: Array<String>) {
        pw.preamble(buffer)
        buffer.table.dump(pw, args)
    }

    private fun dumpConfig(pw: PrintWriter) {
        pw.println("SystemUiServiceComponents configuration:")
        pw.print("vendor component: ")
        pw.println(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        val services: MutableList<String> =
            startables.keys.map({ cls: Class<*> -> cls.simpleName }).toMutableList()

        services.add(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        dumpServiceList(pw, "global", services.toTypedArray())
        dumpServiceList(pw, "per-user", R.array.config_systemUIServiceComponentsPerUser)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, resId: Int) {
        val services: Array<String> = context.resources.getStringArray(resId)
        dumpServiceList(pw, type, services)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, services: Array<String>?) {
        pw.print(type)
        pw.print(": ")
        if (services == null) {
            pw.println("N/A")
            return
        }
        pw.print(services.size)
        pw.println(" services")
        for (i in services.indices) {
            pw.print("  ")
            pw.print(i)
            pw.print(": ")
            pw.println(services[i])
        }
    }

    private fun dumpHelp(pw: PrintWriter) {
        pw.println("Let <invocation> be:")
        pw.println("$ adb shell dumpsys activity service com.android.systemui/.SystemUIService")
        pw.println()

        pw.println("Most common usage:")
        pw.println("$ <invocation> <targets>")
        pw.println("$ <invocation> NotifLog")
        pw.println("$ <invocation> StatusBar FalsingManager BootCompleteCacheImpl")
        pw.println("etc.")
        pw.println()

        pw.println("Special commands:")
        pw.println("$ <invocation> dumpables")
        pw.println("$ <invocation> buffers")
        pw.println("$ <invocation> tables")
        pw.println("$ <invocation> bugreport-critical")
        pw.println("$ <invocation> bugreport-normal")
        pw.println("$ <invocation> config")
        pw.println()

        pw.println("Targets can be listed:")
        pw.println("$ <invocation> --list")
        pw.println("$ <invocation> dumpables --list")
        pw.println("$ <invocation> buffers --list")
        pw.println("$ <invocation> tables --list")
        pw.println()

        pw.println("Show only the most recent N lines of buffers")
        pw.println("$ <invocation> NotifLog --tail 30")
    }

    private fun parseArgs(args: Array<String>): ParsedArgs {
        val mutArgs = args.toMutableList()
        val pArgs = ParsedArgs(args, mutArgs)

        val iterator = mutArgs.iterator()
        while (iterator.hasNext()) {
            val arg = iterator.next()
            if (arg.startsWith("-")) {
                iterator.remove()
                when (arg) {
                    PRIORITY_ARG -> {
                        pArgs.dumpPriority =
                            readArgument(iterator, PRIORITY_ARG) {
                                if (PRIORITY_OPTIONS.contains(it)) {
                                    it
                                } else {
                                    throw IllegalArgumentException()
                                }
                            }
                    }
                    PROTO -> pArgs.proto = true
                    "-t",
                    "--tail" -> {
                        pArgs.tailLength = readArgument(iterator, arg) { it.toInt() }
                    }
                    "-l",
                    "--list" -> {
                        pArgs.listOnly = true
                    }
                    "-h",
                    "--help" -> {
                        pArgs.command = "help"
                    }
                    // This flag is passed as part of the proto dump in Bug reports, we can ignore
                    // it because this is our default behavior.
                    "-a" -> {}
                    else -> {
                        throw ArgParseException("Unknown flag: $arg")
                    }
                }
            }
        }

        if (pArgs.command == null && mutArgs.isNotEmpty() && COMMANDS.contains(mutArgs[0])) {
            pArgs.command = mutArgs.removeAt(0)
        }

        return pArgs
    }

    private fun <T> readArgument(
        iterator: MutableIterator<String>,
        flag: String,
        parser: (arg: String) -> T
    ): T {
        if (!iterator.hasNext()) {
            throw ArgParseException("Missing argument for $flag")
        }
        val value = iterator.next()

        return try {
            parser(value).also { iterator.remove() }
        } catch (e: Exception) {
            throw ArgParseException("Invalid argument '$value' for flag $flag")
        }
    }

    private fun DumpsysEntry.dump(pw: PrintWriter, args: ParsedArgs) =
        when (this) {
            is DumpableEntry -> dumpDumpable(this, pw, args.rawArgs)
            is LogBufferEntry -> dumpBuffer(this, pw, args.tailLength)
            is TableLogBufferEntry -> dumpTableBuffer(this, pw, args.rawArgs)
        }

    private fun Collection<DumpsysEntry>.listOrDumpEntries(pw: PrintWriter, args: ParsedArgs) =
        if (args.listOnly) {
            listTargetNames(this, pw)
        } else {
            forEach { it.dump(pw, args) }
        }

    companion object {
        const val PRIORITY_ARG = "--dump-priority"
        const val PRIORITY_ARG_CRITICAL = "CRITICAL"
        const val PRIORITY_ARG_NORMAL = "NORMAL"
        const val PROTO = "--proto"

        /**
         * Important: do not change this divider without updating any bug report processing tools
         * (e.g. ABT), since this divider is used to determine boundaries for bug report views
         */
        const val DUMPSYS_DUMPABLE_DIVIDER =
            "----------------------------------------------------------------------------"

        /**
         * Important: do not change this divider without updating any bug report processing tools
         * (e.g. ABT), since this divider is used to determine boundaries for bug report views
         */
        const val DUMPSYS_BUFFER_DIVIDER =
            "============================================================================"

        private fun findBestTargetMatch(c: Collection<DumpsysEntry>, target: String) =
            c.asSequence().filter { it.name.endsWith(target) }.minByOrNull { it.name.length }

        private fun findBestProtoTargetMatch(
            c: Collection<DumpableEntry>,
            target: String
        ): ProtoDumpable? =
            c.asSequence()
                .filter { it.name.endsWith(target) }
                .filter { it.dumpable is ProtoDumpable }
                .minByOrNull { it.name.length }
                ?.dumpable as? ProtoDumpable

        private fun PrintWriter.preamble(entry: DumpsysEntry) =
            when (entry) {
                // Historically TableLogBuffer was not separate from dumpables, so they have the
                // same header
                is DumpableEntry,
                is TableLogBufferEntry -> {
                    println()
                    println(entry.name)
                    println(DUMPSYS_DUMPABLE_DIVIDER)
                }
                is LogBufferEntry -> {
                    println()
                    println()
                    println("BUFFER ${entry.name}:")
                    println(DUMPSYS_BUFFER_DIVIDER)
                }
            }

        /**
         * Zero-arg utility to write a [DumpableEntry] to the given [PrintWriter] in a
         * dumpsys-appropriate format.
         */
        private fun dumpDumpable(entry: DumpableEntry, pw: PrintWriter) {
            pw.preamble(entry)
            entry.dumpable.dump(pw, arrayOf())
        }

        /**
         * Zero-arg utility to write a [LogBufferEntry] to the given [PrintWriter] in a
         * dumpsys-appropriate format.
         */
        private fun dumpBuffer(entry: LogBufferEntry, pw: PrintWriter) {
            pw.preamble(entry)
            entry.buffer.dump(pw, 0)
        }

        /**
         * Zero-arg utility to write a [TableLogBufferEntry] to the given [PrintWriter] in a
         * dumpsys-appropriate format.
         */
        private fun dumpTableBuffer(entry: TableLogBufferEntry, pw: PrintWriter) {
            pw.preamble(entry)
            entry.table.dump(pw, arrayOf())
        }

        /**
         * Zero-arg utility to write a [DumpsysEntry] to the given [PrintWriter] in a
         * dumpsys-appropriate format.
         */
        fun DumpsysEntry.dump(pw: PrintWriter) {
            when (this) {
                is DumpableEntry -> dumpDumpable(this, pw)
                is LogBufferEntry -> dumpBuffer(this, pw)
                is TableLogBufferEntry -> dumpTableBuffer(this, pw)
            }
        }

        /** Format [entries] in a dumpsys-appropriate way, using [pw] */
        fun dumpEntries(entries: Collection<DumpsysEntry>, pw: PrintWriter) {
            entries.forEach { it.dump(pw) }
        }
    }
}

private val PRIORITY_OPTIONS = arrayOf(PRIORITY_ARG_CRITICAL, PRIORITY_ARG_NORMAL)

private val COMMANDS =
    arrayOf(
        "bugreport-critical",
        "bugreport-normal",
        "buffers",
        "dumpables",
        "tables",
        "config",
        "help"
    )

private class ParsedArgs(val rawArgs: Array<String>, val nonFlagArgs: List<String>) {
    var dumpPriority: String? = null
    var tailLength: Int = 0
    var command: String? = null
    var listOnly = false
    var proto = false
}

class ArgParseException(message: String) : Exception(message)
