/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionMap;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionType;

import com.oracle.truffle.api.Option;
import com.oracle.truffle.espresso.classfile.JavaVersion;
import com.oracle.truffle.espresso.jdwp.api.JDWPOptions;

@Option.Group(EspressoLanguage.ID)
public final class EspressoOptions {
    private static final Path EMPTY = Paths.get("");
    private static final String PATH_SEPARATOR_INSERT = "\" + java.io.File.pathSeparator + \"";
    private static final String SEMI_COLON = ";";

    /**
     * File.pathSeparator-delimited list of paths.
     */
    private static final OptionType<List<Path>> PATHS_OPTION_TYPE = new OptionType<>("Paths", new Function<String, List<Path>>() {
        @Override
        public List<Path> apply(String paths) {
            try {
                return Collections.unmodifiableList(parsePaths(paths));
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(e);
            }
        }
    });

    private static final OptionType<List<String>> STRINGS_OPTION_TYPE = new OptionType<>("Strings", new Function<String, List<String>>() {
        @Override
        public List<String> apply(String strings) {
            try {
                return Collections.unmodifiableList(splitByFileSeparator(strings));
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(e);
            }
        }
    });

    private static final OptionType<Path> PATH_OPTION_TYPE = new OptionType<>("Path", new Function<String, Path>() {
        @Override
        public Path apply(String path) {
            try {
                return Paths.get(path);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(e);
            }
        }
    });

    private static final OptionType<List<String>> STRINGS_OPTION_TYPE_SEPARATED_BY_SEMI_COLON = new OptionType<>("Strings", new Function<String, List<String>>() {
        @Override
        public List<String> apply(String strings) {
            try {
                return Collections.unmodifiableList(splitBySemiColon(strings));
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException(e);
            }
        }
    });

    @Option(help = "User-defined system properties.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<value>") //
    public static final OptionKey<OptionMap<String>> Properties = OptionKey.mapOf(String.class);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories, JAR archives, and ZIP archives to search for class files.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<Path>> Classpath = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "Specifies in which module the main class is located. Can also specify the main class name by appending it after a \\\"/\\\"", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<module>[/<mainclass>]") //
    public static final OptionKey<String> Module = new OptionKey<>("");

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories to search for modules.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<Path>> ModulePath = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of root modules beyond the initial module.\\nEquivalent to '--add-modules=<module>'", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<module>" + PATH_SEPARATOR_INSERT + "<module>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<String>> AddModules = new OptionKey<>(Collections.emptyList(), STRINGS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "'separated list of root modules beyond the initial module.\\nEquivalent to '--add-reads=<module>'", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<module>" + PATH_SEPARATOR_INSERT + "<module>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<String>> AddReads = new OptionKey<>(Collections.emptyList(), STRINGS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of root modules beyond the initial module.\\nEquivalent to '--add-exports=<module>'", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<module>" + PATH_SEPARATOR_INSERT + "<module>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<String>> AddExports = new OptionKey<>(Collections.emptyList(), STRINGS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of root modules beyond the initial module.\\nEquivalent to '--add-opens=<module>'", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<module>" + PATH_SEPARATOR_INSERT + "<module>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<String>> AddOpens = new OptionKey<>(Collections.emptyList(), STRINGS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of modules that are permitted to perform restricted native operations.\\nEquivalent to '--enable-native-access=<module>'", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<module>" + PATH_SEPARATOR_INSERT + "<module>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<String>> EnableNativeAccess = new OptionKey<>(Collections.emptyList(), STRINGS_OPTION_TYPE);

    @Option(help = "Allow or deny access to code and data outside the Java runtime " +
                    "by code in modules for which native access is not explicitly enabled. " +
                    "<value> is one of `deny`, `warn` or `allow`. The default value is `warn`. " +
                    "This option will be removed in a future release." +
                    "\\nEquivalent to '--illegal-native-access=<value>'", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "warn|deny|allow") //
    public static final OptionKey<String> IllegalNativeAccess = new OptionKey<>("");

    @Option(help = "Installation directory for Java Runtime Environment (JRE).", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<path>") //
    public static final OptionKey<Path> JavaHome = new OptionKey<>(EMPTY, PATH_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories to search for Espresso's (lib)?jvm.(so|dll|dylib).", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<Path>> JVMLibraryPath = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT +
                    "' separated list of directories, JAR files, and ZIP archives to search for boot class files. These are used in place of the boot class files included in the JDK.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "...") //
    public static final OptionKey<List<Path>> BootClasspath = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories, JAR files, and ZIP archives to append to the front of the default bootstrap class path.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "..." //
    ) //
    public static final OptionKey<List<Path>> BootClasspathAppend = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories, JAR files, and ZIP archives to prepend to the end of the default bootstrap class path.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "..." //
    ) //
    public static final OptionKey<List<Path>> BootClasspathPrepend = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories to search for user libraries.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "..." //
    ) //
    public static final OptionKey<List<Path>> JavaLibraryPath = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories to search for system libraries.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "..." //
    ) //
    public static final OptionKey<List<Path>> BootLibraryPath = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + PATH_SEPARATOR_INSERT + "' separated list of directories to search for extensions.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<path>" + PATH_SEPARATOR_INSERT + "<path>" + PATH_SEPARATOR_INSERT + "..." //
    ) //
    public static final OptionKey<List<Path>> ExtDirs = new OptionKey<>(Collections.emptyList(), PATHS_OPTION_TYPE);

    @Option(help = "A '" + SEMI_COLON + "' separated list of fully qualified interface names that enables interface type mapping in polyglot usage.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "my.first.MyInterface;my.second.MySecondInterface;...") //
    public static final OptionKey<List<String>> PolyglotInterfaceMappings = new OptionKey<>(Collections.emptyList(), STRINGS_OPTION_TYPE_SEPARATED_BY_SEMI_COLON);

    @Option(help = "Option to enable target type conversion by specifying a conversion class.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "java.PolyglotTypeConverters.java.lang.Optional=my.type.conversion.Implementation") //
    public static final OptionKey<OptionMap<String>> PolyglotTypeConverters = OptionKey.mapOf(String.class);

    @Option(help = "Option to enable target type conversions for foreign objects using type hints from generics signatures.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableGenericTypeHints = new OptionKey<>(true);

    @Option(help = "Enable assertions.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableAssertions = new OptionKey<>(false);

    @Option(help = "Enable system assertions.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableSystemAssertions = new OptionKey<>(false);

    @Option(help = "Enable extended NullPointerException message.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> ShowCodeDetailsInExceptionMessages = new OptionKey<>(true);

    @Option(help = "Enable implicit interop.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableImplicitInterop = new OptionKey<>(true);

    public static List<Path> parsePaths(String paths) {
        List<Path> list = new ArrayList<>();
        for (String path : splitByFileSeparator(paths)) {
            list.add(Paths.get(path));
        }
        return list;
    }

    private static List<String> splitByFileSeparator(String strings) {
        return new ArrayList<>(Arrays.asList(strings.split(File.pathSeparator)));
    }

    private static List<String> splitBySemiColon(String strings) {
        return new ArrayList<>(Arrays.asList(strings.split(EspressoOptions.SEMI_COLON)));
    }

    public enum SpecComplianceMode {
        STRICT,
        HOTSPOT
    }

    private static final OptionType<SpecComplianceMode> SPEC_COMPLIANCE_OPTION_TYPE = new OptionType<>("SpecCompliance", new Function<String, SpecComplianceMode>() {
        @Override
        public SpecComplianceMode apply(String s) {
            try {
                return SpecComplianceMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--java.SpecCompliance: Mode can be 'strict' or 'hotspot'.");
            }
        }
    });

    @Option(help = "Force mimicking of hotspot behavior on unrespected specs points", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "hotspot|strict") //
    public static final OptionKey<SpecComplianceMode> SpecCompliance = new OptionKey<>(SpecComplianceMode.HOTSPOT, SPEC_COMPLIANCE_OPTION_TYPE);

    /**
     * Selects the memory model used for shared-memory accesses and synchronisation (Sync-as-Share).
     * {@code SHARED} is the stock, sequentially-consistent shared-memory behaviour (identity: the
     * seam folds away under partial evaluation, so there is zero overhead). {@code LINEAR} selects
     * the Linear version-controlled memory model.
     */
    public enum MemoryModelMode {
        SHARED,
        LINEAR,
        BRANCHING
    }

    private static final OptionType<MemoryModelMode> MEMORY_MODEL_OPTION_TYPE = new OptionType<>("MemoryModel", new Function<String, MemoryModelMode>() {
        @Override
        public MemoryModelMode apply(String s) {
            try {
                return MemoryModelMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--java.MemoryModel: Mode can be 'shared', 'linear' or 'branching'.");
            }
        }
    });

    @Option(help = "Selects the memory model: 'shared' (stock sequentially-consistent shared memory), 'linear' (Sync-as-Share Linear version-controlled memory), or 'branching' (Sync-as-Share Branching commit-DAG model).", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "shared|linear|branching") //
    public static final OptionKey<MemoryModelMode> MemoryModel = new OptionKey<>(MemoryModelMode.SHARED, MEMORY_MODEL_OPTION_TYPE);

    /**
     * Selects the <em>backing representation</em> for {@code MemoryModel=branching} (ignored
     * otherwise). Both are the same model — identical accept/reject in every case — and differ only in
     * performance, analogous to {@link #MemoryModelHistory} for linear. {@code DAG} is the commit-DAG
     * of information flow (the paper's definitional representation; conflict check walks the other
     * branch's divergence, O(divergence)). {@code EPOCH} is the DAG-free realisation — per-thread
     * vector clocks + per-sync-object last-writer epoch tables — whose conflict check is a per-location
     * lookup, O(working set), removing the contended-lock walk. See epoch-model.md.
     */
    public enum BranchingBackingMode {
        DAG,
        EPOCH
    }

    private static final OptionType<BranchingBackingMode> BRANCHING_BACKING_OPTION_TYPE = new OptionType<>("BranchingBacking", new Function<String, BranchingBackingMode>() {
        @Override
        public BranchingBackingMode apply(String s) {
            try {
                return BranchingBackingMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--java.BranchingBacking: backing can be 'dag' or 'epoch'.");
            }
        }
    });

    @Option(help = "Backing representation for MemoryModel=branching: 'dag' (commit-DAG; conflict check walks divergence) or 'epoch' (vector clocks + per-sync-object last-writer epoch tables; conflict check is a per-location lookup). Behaviourally identical; different performance. Ignored unless branching.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "dag|epoch") //
    public static final OptionKey<BranchingBackingMode> BranchingBacking = new OptionKey<>(BranchingBackingMode.DAG, BRANCHING_BACKING_OPTION_TYPE);

    /**
     * Selects the concurrency strategy for the Linear model's global history {@code g} (ignored under
     * {@code MemoryModel=shared}). A design-exploration knob: the strategies are behaviourally
     * equivalent but trade differently under contention (see concurrency-design.md).
     * {@code RWLOCK} is the read/write-split single lock (pulls + empty pushes concurrent; committing
     * pushes serialise). {@code WATERMARK} is per-location stripe locks + a commit-order watermark
     * (disjoint committing pushes run in parallel; only an O(1) retire is serial).
     */
    public enum MemoryModelHistoryMode {
        RWLOCK,
        WATERMARK
    }

    private static final OptionType<MemoryModelHistoryMode> MEMORY_MODEL_HISTORY_OPTION_TYPE = new OptionType<>("MemoryModelHistory", new Function<String, MemoryModelHistoryMode>() {
        @Override
        public MemoryModelHistoryMode apply(String s) {
            try {
                return MemoryModelHistoryMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--java.MemoryModelHistory: strategy can be 'rwlock' or 'watermark'.");
            }
        }
    });

    @Option(help = "Global-history concurrency strategy for MemoryModel=linear: 'rwlock' (read/write-split single lock) or 'watermark' (per-location stripe locks + commit-order watermark). A design-exploration knob; ignored under shared.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "rwlock|watermark") //
    public static final OptionKey<MemoryModelHistoryMode> MemoryModelHistory = new OptionKey<>(MemoryModelHistoryMode.RWLOCK, MEMORY_MODEL_HISTORY_OPTION_TYPE);

    /**
     * History-collapsing (reclamation) policy for the Linear model (ignored under {@code shared} and
     * {@code branching}). {@code OFF} keeps all history forever (unbounded growth).
     *
     * <p>{@code THRESHOLD} reclaims, on a cadence, every version older than the
     * <em>furthest-behind live thread</em>: the collapse threshold {@code T = min} over live threads of
     * their observed base. For each location the newest version {@code <= T} is retained (a moving
     * {@code t=0}) and everything older is dropped. Cheap (one number, drops the settled tail) but a
     * single thread parked at a low base pins {@code [T, head]} — the whole interval, including the
     * interior no one reads.
     *
     * <p>{@code SQUASH} reclaims using the <em>full set</em> of live bases: for each location it keeps
     * only the version each live base <em>resolves to</em> ({@code newest <= b}) plus the head (needed
     * for conflict detection), dropping the shadowed interior between them. This reclaims the interior
     * that {@code THRESHOLD} cannot when threads are spread out (a parked coordinator plus an advanced
     * worker), at the cost of examining every version against the base set.
     */
    public enum MemoryModelCollapseMode {
        OFF,
        THRESHOLD,
        SQUASH
    }

    private static final OptionType<MemoryModelCollapseMode> MEMORY_MODEL_COLLAPSE_OPTION_TYPE = new OptionType<>("MemoryModelCollapse", new Function<String, MemoryModelCollapseMode>() {
        @Override
        public MemoryModelCollapseMode apply(String s) {
            try {
                return MemoryModelCollapseMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--java.MemoryModelCollapse: policy can be 'off', 'threshold' or 'squash'.");
            }
        }
    });

    @Option(help = "History-collapsing policy for MemoryModel=linear: 'off' (keep all history), 'threshold' (drop versions older than the furthest-behind live thread) or 'squash' (also drop the interior no live base resolves to). On a cadence; ignored under shared/branching.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "off|threshold|squash") //
    public static final OptionKey<MemoryModelCollapseMode> MemoryModelCollapse = new OptionKey<>(MemoryModelCollapseMode.OFF, MEMORY_MODEL_COLLAPSE_OPTION_TYPE);

    @Option(help = "Cadence for MemoryModelCollapse=watermark: run a collapse once every N committing sync pushes (default 1000). Smaller = tighter memory, more collapse overhead.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<int>") //
    public static final OptionKey<Integer> MemoryModelCollapseEvery = new OptionKey<>(1000);

    public enum VerifyMode {
        NONE,
        REMOTE, // Verifies all bytecodes not loaded by the bootstrap class loader.
        ALL
    }

    private static final OptionType<VerifyMode> VERIFY_MODE_OPTION_TYPE = new OptionType<>("VerifyMode", new Function<String, VerifyMode>() {
        @Override
        public VerifyMode apply(String s) {
            try {
                return VerifyMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("-Xverify: Mode can be 'none', 'remote' or 'all'.");
            }
        }
    });

    @Option(help = "Sets the mode of the bytecode verifier.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "remote|none|all" //
    ) //
    public static final OptionKey<VerifyMode> Verify = new OptionKey<>(VerifyMode.REMOTE, VERIFY_MODE_OPTION_TYPE);

    @Option(help = "Replace regular expression engine with a TRegex implementation.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> UseTRegex = new OptionKey<>(false);

    @Option(help = "Speculatively inline field accessors.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> BytecodeLevelInlining = new OptionKey<>(true);

    @Option(help = "Enable inlining through method handle calls.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> InlineMethodHandle = new OptionKey<>(true);

    @Option(help = "All method handle call site have a different inline cache.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> SplitMethodHandles = new OptionKey<>(false);

    @Option(help = "Enable string representation sharing between host and guest (If both have the same string representation). " +
                    "When enabled, reflective modifications to the underlying array of guest strings may reflect on host strings, and vice-versa. ", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> StringSharing = new OptionKey<>(true);

    public enum LivenessAnalysisMode {
        NONE,
        ALL,
        AUTO // Activate liveness analysis for certain methods.
    }

    private static final OptionType<LivenessAnalysisMode> LIVENESS_ANALYSIS_MODE_OPTION_TYPE = new OptionType<>("LivenessAnalysisMode", new Function<String, LivenessAnalysisMode>() {
        @Override
        public LivenessAnalysisMode apply(String s) {
            // To maintain backwards compatibility with the boolean option.
            if ("true".equalsIgnoreCase(s)) {
                return LivenessAnalysisMode.ALL;
            }
            if ("false".equalsIgnoreCase(s)) {
                return LivenessAnalysisMode.NONE;
            }
            try {
                return LivenessAnalysisMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--java.LivenessAnalysis can only be 'none'|'false', 'auto' or 'all'|'true'.");
            }
        }
    });

    @Option(help = "Controls static liveness analysis of bytecodes, allowing to clear local variables during execution if they become stale.\\n" + //
                    "Liveness analysis, if enabled, only affects compiled code.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "auto|true|all|false|none") //
    public static final OptionKey<LivenessAnalysisMode> LivenessAnalysis = new OptionKey<>(LivenessAnalysisMode.AUTO, LIVENESS_ANALYSIS_MODE_OPTION_TYPE);

    @Option(help = "Minimum number of locals to run liveness analysis.\\n" + //
                    "Liveness analysis, if enabled, only affects compiled code.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "[0, 65535]") //
    public static final OptionKey<Integer> LivenessAnalysisMinimumLocals = new OptionKey<>(8);

    @Option(help = "Enable Class Hierarchy Analysis, which optimizes instanceof checks and virtual method calls by keeping track of descendants of a given class or interface.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> CHA = new OptionKey<>(true);

    private static final OptionType<com.oracle.truffle.espresso.jdwp.api.JDWPOptions> JDWP_OPTIONS_OPTION_TYPE = new OptionType<>("JDWPOptions", new Function<String, JDWPOptions>() {

        private boolean yesOrNo(String key, String value) {
            if (!"y".equals(value) && !"n".equals(value)) {
                throw new IllegalArgumentException("Invalid JDWP option value: " + key + " can be only 'y' or 'n'.");
            }
            return "y".equals(value);
        }

        @Override
        public JDWPOptions apply(String s) {
            final String[] options = s.split(",");
            String transport = null;
            String host = null;
            int port = 0;
            boolean server = false;
            boolean suspend = true;

            for (String keyValue : options) {
                int equalsIndex = keyValue.indexOf('=');
                if (equalsIndex <= 0) {
                    throw new IllegalArgumentException("JDWP options must be a comma separated list of key=value pairs.");
                }
                String key = keyValue.substring(0, equalsIndex);
                String value = keyValue.substring(equalsIndex + 1);
                switch (key) {
                    case "address":
                        String inputHost = null;
                        int inputPort = 0;
                        if (!value.isEmpty()) {
                            int colonIndex = value.indexOf(':');
                            if (colonIndex > 0) {
                                inputHost = value.substring(0, colonIndex);
                            }
                            String portStr = value.substring(colonIndex + 1);
                            long realValue;
                            try {
                                realValue = Long.valueOf(portStr);
                                if (realValue < 0 || realValue > 65535) {
                                    throw new IllegalArgumentException("Invalid JDWP option, address: " + value + ". Must be in the 0 - 65535 range.");
                                }
                            } catch (NumberFormatException ex) {
                                throw new IllegalArgumentException("Invalid JDWP option, address: " + value + ". Port is not a number. Must be a number in the 0 - 65535 range.");
                            }
                            inputPort = (int) realValue;
                        }
                        host = inputHost;
                        port = inputPort;
                        break;
                    case "transport":
                        if (!"dt_socket".equals(value)) {
                            throw new IllegalArgumentException("Invalid transport " + value + ". Espresso only supports dt_socket currently.");
                        }
                        transport = value;
                        break;
                    case "server":
                        server = yesOrNo(key, value);
                        break;
                    case "suspend":
                        suspend = yesOrNo(key, value);
                        break;
                    case "includevirtualthreads":
                        boolean includevirtualthreads = yesOrNo(key, value);
                        if (includevirtualthreads) {
                            throw new IllegalArgumentException("Invalid includevirtualthreads setting " + value + ". Espresso only supports n currently.");
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid JDWP option: " + key + ". Supported options: 'transport', 'address', 'server', 'suspend', and 'includevirtualthreads=n'.");
                }
            }
            return new JDWPOptions(transport, host, port, server, suspend);
        }
    });

    @Option(help = "JDWP agent Options. e.g. -agentlib:jdwp=transport=dt_socket,server=y,address=localhost:8000,suspend=y", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "[transport=dt_socket],[server=y|n],[address=[<host>:]<port>,[suspend=y|n]]") //
    public static final OptionKey<JDWPOptions> JDWPOptions = new OptionKey<>(null, JDWP_OPTIONS_OPTION_TYPE);

    @Option(help = "Enable experimental java.lang.management APIs. Incur a bookkeeping overhead.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> EnableManagement = new OptionKey<>(true);

    @Option(help = "Enable support for threads. " +
                    "In single-threaded mode, Thread.start is disabled, weak references and finalizers won't be processed. " +
                    "Lock operations may be optimized away.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> MultiThreaded = new OptionKey<>(true);

    @Option(help = "Enables graceful teardown of the VM on exit. " +
                    "Rather than abruptly terminating execution, gives all leftover non-daemon thread some leeway to finish executing in guest.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> SoftExit = new OptionKey<>(false);

    @Option(help = "Allows Espresso to use host System.exit() on context exit when there are unresponsive threads. " +
                    "This should not be used in most cases as it will take down the whole host VM abruptly, possibly preventing other languages from performing their own exit sequence.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> ExitHost = new OptionKey<>(false);

    @Option(help = "Enables espresso runtime timers.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableTimers = new OptionKey<>(false);

    @Option(help = "Enable polyglot support in Espresso.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> Polyglot = new OptionKey<>(false);

    @Option(help = "Enable built in polyglot collection support in Espresso.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> BuiltInPolyglotCollections = new OptionKey<>(false);

    @Option(help = "Enable hotspot extension API.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> HotSwapAPI = new OptionKey<>(false);

    @Option(help = "Use Custom ClassLoader for Bindings, allowing the addition of new locations for loading.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> UseBindingsLoader = new OptionKey<>(false);

    @Option(help = "Expose the <JavaVM> binding.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> ExposeNativeJavaVM = new OptionKey<>(false);

    @Option(help = "User-specified classlist used to warmup Espresso during context pre-initialization. The file should contain one class per line (see lib/classlist for an example).", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL) //
    public static final OptionKey<Path> PreInitializationClasslist = new OptionKey<>(EMPTY, PATH_OPTION_TYPE);

    private static final OptionType<Long> SIZE_OPTION_TYPE = new OptionType<>("Size", new Function<String, Long>() {
        private static final int K = 1024;

        @Override
        public Long apply(String size) {
            int idx = 0;
            int len = size.length();
            for (int i = 0; i < len; i++) {
                if (Character.isDigit(size.charAt(i))) {
                    idx++;
                } else {
                    break;
                }
            }

            if (idx == 0) {
                throw new IllegalArgumentException("Not starting with digits: " + size);
            }
            if (len - idx > 1) {
                throw new IllegalArgumentException("Unit prefix can be at most one character: " + size);
            }

            long result = Long.parseLong(size.substring(0, idx));

            if (idx < len) {
                switch (size.charAt(idx)) {
                    case 'T': // fallthrough
                    case 't':
                        return result * K * K * K * K;
                    case 'G': // fallthrough
                    case 'g':
                        return result * K * K * K;
                    case 'M': // fallthrough
                    case 'm':
                        return result * K * K;
                    case 'K': // fallthrough
                    case 'k':
                        return result * K;
                    default:
                        throw new IllegalArgumentException("Unrecognized unit prefix: " + size + " use `T`, `G`, `M`, or `k`.");
                }
            }
            return result;

        }
    });

    @Option(help = "Maximum total size of NIO direct-buffer allocations.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.STABLE, usageSyntax = "<size>[<unit>]") //
    public static final OptionKey<Long> MaxDirectMemorySize = new OptionKey<>(-1L, SIZE_OPTION_TYPE);

    @Option(help = "Load native agents from standard library paths. \\n" +
                    "Keys represent the agent library name, values are the corresponding agent options.\\n" +
                    "Agents are not fully implemented yet.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<agentOptions>") //
    public static final OptionKey<OptionMap<String>> AgentLib = OptionKey.mapOf(String.class);

    @Option(help = "Load native agents from an absolute path. \\n" +
                    "Keys represent the agent library full absolute path, values are the corresponding agent options.\\n" +
                    "Agents are not fully implemented yet.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<librarypath>=<agentOptions>") //
    public static final OptionKey<OptionMap<String>> AgentPath = OptionKey.mapOf(String.class);

    @Option(help = "Load a Java programming language agent for the given jar file. \\n" +
                    "Keys represent the jar path, values are the corresponding agent options.\\n" +
                    "Agents are not fully implemented yet.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<agent>=<agentOptions>") //
    public static final OptionKey<OptionMap<String>> JavaAgent = OptionKey.mapOf(String.class);

    @Option(help = "Used internally to keep track of the command line arguments given to the vm in order to support VM.getRuntimeArguments().\\n" +
                    "Setting this option is the responsibility of the context creator if such support is required.\\n" +
                    "Usage:\\n" +
                    "For each argument [arg<i>] passed to the context (excluding the main class args):\\n" +
                    "    builder.option(java.VMArguments.<i>, [arg<i>]);", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "<argument>") //
    public static final OptionKey<OptionMap<String>> VMArguments = OptionKey.mapOf(String.class);

    @Option(help = "Native backend used by Espresso, if not specified, Espresso will pick one depending on the environment.\\n" +
                    "If native access is disabled, native methods will be emulated in Java with limited functionality.\\n" +
                    "For example there will be no access to LibAWT.", //
                    category = OptionCategory.EXPERT,  //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<nativeBackend>") //
    public static final OptionKey<String> NativeBackend = new OptionKey<>("");

    @Option(help = "NativeMemory used by Espresso. Can only be specified with no-native backend where memory can be fully virtualized.", //
                    category = OptionCategory.EXPERT,  //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<nativeMemory>") //
    public static final OptionKey<String> NativeMemory = new OptionKey<>("");

    @Option(help = "Enable use of a custom Espresso implementation of boot libraries, which allows for not entering native code.\\n" +
                    "Will be automatically enabled if there is NO native access.\\n" +
                    "For example, this will replace the usual 'libjava'. Missing implementations will thus fail with 'UnsatifiedLinkError'.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> UseEspressoLibs = new OptionKey<>(false);

    @Option(help = "Whether to perform bounds checks on array accesses via Unsafe!", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "true|false") //
    public static final OptionKey<Boolean> CheckUnsafeArrayBounds = new OptionKey<>(false);

    @Option(help = "Enables the signal API (sun.misc.Signal or jdk.internal.misc.Signal).", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableSignals = new OptionKey<>(false);

    @Option(help = "Enables native JVMTI agents. Support is currently very limited.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableNativeAgents = new OptionKey<>(false);

    @Option(help = "Maximum bytecode size (in bytes) for a method to be considered trivial.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "[0, 65535]") //
    public static final OptionKey<Integer> TrivialMethodSize = new OptionKey<>(18);

    @Option(help = "Use the host FinalReference to implement guest finalizers. If set to false, FinalReference will fallback to WeakReference semantics.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> UseHostFinalReference = new OptionKey<>(true);

    public enum JImageMode {
        NATIVE,
        JAVA,
    }

    private static final OptionType<JImageMode> JIMAGE_MODE_OPTION_TYPE = new OptionType<>("JImageMode", new Function<String, JImageMode>() {
        @Override
        public JImageMode apply(String s) {
            try {
                return JImageMode.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("JImage: Mode can be 'native', 'java'.");
            }
        }
    });

    @Option(help = "Selects the jimage reader.", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL) //
    public static final OptionKey<JImageMode> JImage = new OptionKey<>(JImageMode.JAVA, JIMAGE_MODE_OPTION_TYPE);

    @Option(help = "Enables preview features.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.STABLE, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnablePreview = new OptionKey<>(false);

    @Option(help = "Enables the WhiteBox API.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> WhiteBoxAPI = new OptionKey<>(false);

    @Option(help = "Enables the JVMCI API inside the context.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableJVMCI = new OptionKey<>(false);

    @Option(help = "Expose the <JVMCI_HELPER> binding to support external/host JVMCI.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> ExposeJVMCIHelper = new OptionKey<>(false);

    public enum GuestFieldOffsetStrategyEnum {
        safety,
        compact,
        graal
    }

    @Option(help = "Guest field offset strategy. The safety strategy will help catch some wrong usages of the unsafe API.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "safety|compact|graal") //
    public static final OptionKey<GuestFieldOffsetStrategyEnum> GuestFieldOffsetStrategy = new OptionKey<>(GuestFieldOffsetStrategyEnum.graal);

    @Option(help = "Selects a specific runtime resource id (espresso-runtime-resource-<id>).", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "jdk21|openjdk21|...") //
    public static final OptionKey<String> RuntimeResourceId = new OptionKey<>("");

    @Option(help = "Enables the Continuum API.", //
                    category = OptionCategory.USER, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> Continuum = new OptionKey<>(false);

    @Option(help = "Forces frame analysis to run for all loaded classes. Used for testing.", //
                    category = OptionCategory.INTERNAL, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") public static final OptionKey<Boolean> EagerFrameAnalysis = new OptionKey<>(false);

    public enum XShareOption {
        auto,
        on,
        off,
        dump
    }

    @Option(help = "Sets the class data sharing (CDS) mode.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "auto|on|off|dump") //
    public static final OptionKey<XShareOption> CDS = new OptionKey<>(XShareOption.off);

    @Option(help = "Overrides the default path to the (static) CDS archive.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<path>") //
    public static final OptionKey<Path> SharedArchiveFile = new OptionKey<>(EMPTY, PATH_OPTION_TYPE);

    @Option(help = "Sets the amount of time, in ms, various thread requests (such as 'Thread.getStackTrace()') will wait for when the requested thread is considered unresponsive w.r.t. espresso.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<duration in ms>") //
    public static final OptionKey<Integer> ThreadRequestGracePeriod = new OptionKey<>(100);

    public enum MemoryAccessOption {
        allow,
        warn,
        debug,
        deny,
        defaultValue // undocumented sentinel value
    }

    @Option(help = "Allow or deny usage of unsupported API sun.misc.Unsafe", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "allow|warn|debug|deny") //
    public static final OptionKey<MemoryAccessOption> SunMiscUnsafeMemoryAccess = new OptionKey<>(MemoryAccessOption.defaultValue);

    @Option(help = "Enable advanced class redefinition.", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EnableAdvancedRedefinition = new OptionKey<>(false);

    @Option(help = "The maximum number of lines in the stack trace for Java exceptions", //
                    category = OptionCategory.EXPERT, //
                    stability = OptionStability.EXPERIMENTAL, //
                    usageSyntax = "<depth>>") //
    // HotSpot's MaxJavaStackTraceDepth is 1024 by default
    public static final OptionKey<Integer> MaxJavaStackTraceDepth = new OptionKey<>(32);

    /**
     * Property used to force liveness analysis to also be applied by the interpreter. For testing
     * purpose only. Use a host property rather than an option. An option would slow interpreter
     * considerably.
     */
    public static final boolean LivenessAnalysisInInterpreter = booleanProperty("espresso.liveness.interpreter", false);

    // Properties for FinalizationSupport e.g. --vm.Despresso.finalization.UnsafeOverride=false .
    public static final boolean UnsafeOverride = booleanProperty("espresso.finalization.UnsafeOverride", true);
    public static final boolean InjectClasses = booleanProperty("espresso.finalization.InjectClasses", !JavaVersion.HOST_VERSION.java19OrLater());

    private static boolean booleanProperty(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : value.equalsIgnoreCase("true");
    }
}
