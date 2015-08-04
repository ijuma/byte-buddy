package net.bytebuddy.dynamic.scaffold;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.MethodTransformer;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.LoadedTypeInitializer;
import net.bytebuddy.implementation.attribute.MethodAttributeAppender;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.LatentMethodMatcher;

import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.utility.ByteBuddyCommons.join;

/**
 * A method registry is responsible for storing information on how a method is intercepted.
 */
public interface MethodRegistry {

    /**
     * Prepends the given method definition to this method registry, i.e. this configuration is applied first.
     *
     * @param methodMatcher            A matcher to identify all entries that are to be matched.
     * @param handler                  The handler to instrument any matched method.
     * @param attributeAppenderFactory A method attribute appender to apply to any matched method.
     * @return A mutated version of this method registry.
     */
    MethodRegistry prepend(LatentMethodMatcher methodMatcher,
                           Handler handler,
                           MethodAttributeAppender.Factory attributeAppenderFactory,
                           MethodTransformer methodTransformer);

    /**
     * Appends the given method definition to this method registry, i.e. this configuration is applied last.
     *
     * @param methodMatcher            A matcher to identify all entries that are to be matched.
     * @param handler                  The handler to instrument any matched method.
     * @param attributeAppenderFactory A method attribute appender to apply to any matched method.
     * @return A mutated version of this method registry.
     */
    MethodRegistry append(LatentMethodMatcher methodMatcher,
                          Handler handler,
                          MethodAttributeAppender.Factory attributeAppenderFactory,
                          MethodTransformer methodTransformer);

    /**
     * Prepares this method registry.
     *
     * @param instrumentedType    The instrumented type that should be created.
     * @param methodGraphCompiler The method graph compiler to be used for analyzing the fully assembled instrumented type.
     * @param methodFilter        A filter that only matches methods that should be instrumented.
     * @return A prepared version of this method registry.
     */
    Prepared prepare(InstrumentedType instrumentedType, MethodGraph.Compiler methodGraphCompiler, LatentMethodMatcher methodFilter);

    /**
     * A handler for implementing a method.
     */
    interface Handler {

        /**
         * Prepares the instrumented type for this handler.
         *
         * @param instrumentedType The instrumented type to prepare.
         * @return The prepared instrumented type.
         */
        InstrumentedType prepare(InstrumentedType instrumentedType);

        /**
         * Compiles this handler.
         *
         * @param implementationTarget The implementation target to compile this handler for.
         * @return A compiled handler.
         */
        Handler.Compiled compile(Implementation.Target implementationTarget);

        /**
         * A compiled handler for implementing a method.
         */
        interface Compiled {

            /**
             * Assembles this compiled entry with a method attribute appender.
             *
             * @param methodDescription The method description to apply with this handler.
             * @param attributeAppender The method attribute appender to apply together with this handler.
             * @return A method pool entry representing this handler and the given attribute appender.
             */
            TypeWriter.MethodPool.Record assemble(MethodDescription methodDescription, MethodAttributeAppender attributeAppender);
        }

        /**
         * A handler for defining an abstract or native method.
         */
        enum ForAbstractMethod implements Handler, Compiled {

            INSTANCE;

            @Override
            public InstrumentedType prepare(InstrumentedType instrumentedType) {
                return instrumentedType;
            }

            @Override
            public Compiled compile(Implementation.Target implementationTarget) {
                return this;
            }

            @Override
            public TypeWriter.MethodPool.Record assemble(MethodDescription methodDescription, MethodAttributeAppender attributeAppender) {
                return new TypeWriter.MethodPool.Record.ForDefinedMethod.WithoutBody(methodDescription, attributeAppender);
            }

            @Override
            public String toString() {
                return "MethodRegistry.Handler.ForAbstractMethod." + name();
            }
        }

        /**
         * A handler for a method that is implemented as byte code.
         */
        class ForImplementation implements Handler {

            /**
             * The implementation to apply.
             */
            private final Implementation implementation;

            /**
             * Creates a new handler for implementing a method with byte code.
             *
             * @param implementation   The implementation to apply.
             */
            public ForImplementation(Implementation implementation) {
                this.implementation = implementation;
            }

            @Override
            public InstrumentedType prepare(InstrumentedType instrumentedType) {
                return implementation.prepare(instrumentedType);
            }

            @Override
            public Compiled compile(Implementation.Target implementationTarget) {
                return new Compiled(implementation.appender(implementationTarget));
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && implementation.equals(((ForImplementation) other).implementation);
            }

            @Override
            public int hashCode() {
                return implementation.hashCode();
            }

            @Override
            public String toString() {
                return "MethodRegistry.Handler.ForImplementation{" +
                        "implementation=" + implementation +
                        '}';
            }

            /**
             * A compiled handler for implementing a method.
             */
            protected static class Compiled implements Handler.Compiled {

                /**
                 * The byte code appender to apply.
                 */
                private final ByteCodeAppender byteCodeAppender;

                /**
                 * Creates a new compiled handler for a method implementation.
                 *
                 * @param byteCodeAppender The byte code appender to apply.
                 */
                protected Compiled(ByteCodeAppender byteCodeAppender) {
                    this.byteCodeAppender = byteCodeAppender;
                }

                @Override
                public TypeWriter.MethodPool.Record assemble(MethodDescription methodDescription, MethodAttributeAppender attributeAppender) {
                    return new TypeWriter.MethodPool.Record.ForDefinedMethod.WithBody(methodDescription, byteCodeAppender, attributeAppender);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && byteCodeAppender.equals(((Compiled) other).byteCodeAppender);
                }

                @Override
                public int hashCode() {
                    return byteCodeAppender.hashCode();
                }

                @Override
                public String toString() {
                    return "MethodRegistry.Handler.ForImplementation.Compiled{" +
                            "byteCodeAppender=" + byteCodeAppender +
                            '}';
                }
            }
        }

        /**
         * A handler for defining a default annotation value for a method.
         */
        class ForAnnotationValue implements Handler, Compiled {

            /**
             * The annotation value to set as a default value.
             */
            private final Object annotationValue;

            /**
             * Creates a handler for defining a default annotation value for a method.
             *
             * @param annotationValue  The annotation value to set as a default value.
             */
            protected ForAnnotationValue(Object annotationValue) {
                this.annotationValue = annotationValue;
            }

            /**
             * Represents the given value as an annotation default value handler after validating its suitability.
             *
             * @param annotationValue  The annotation value to represent.
             * @return A handler for setting the given value as a default value for instrumented methods.
             */
            public static Handler of(Object annotationValue) {
                TypeDescription typeDescription = new TypeDescription.ForLoadedType(annotationValue.getClass());
                if (!typeDescription.isAnnotationValue() && !typeDescription.isPrimitiveWrapper()) {
                    throw new IllegalArgumentException("Does not describe an annotation value: " + annotationValue);
                }
                return new ForAnnotationValue(annotationValue);
            }

            @Override
            public InstrumentedType prepare(InstrumentedType instrumentedType) {
                return instrumentedType;
            }

            @Override
            public Compiled compile(Implementation.Target implementationTarget) {
                return this;
            }

            @Override
            public TypeWriter.MethodPool.Record assemble(MethodDescription methodDescription, MethodAttributeAppender attributeAppender) {
                return new TypeWriter.MethodPool.Record.ForDefinedMethod.WithAnnotationDefaultValue(methodDescription, annotationValue, attributeAppender);
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && annotationValue.equals(((ForAnnotationValue) other).annotationValue);
            }

            @Override
            public int hashCode() {
                return annotationValue.hashCode();
            }

            @Override
            public String toString() {
                return "MethodRegistry.Handler.ForAnnotationValue{" +
                        "annotationValue=" + annotationValue +
                        '}';
            }
        }

        /**
         * A handler for implementing a visibility bridge.
         */
        enum ForVisibilityBridge implements Handler {

            /**
             * The singleton instance.
             */
            INSTANCE;

            @Override
            public InstrumentedType prepare(InstrumentedType instrumentedType) {
                throw new IllegalStateException("A visibility bridge handler must not apply any preparations");
            }

            @Override
            public Compiled compile(Implementation.Target implementationTarget) {
                return new Compiled(implementationTarget.getTypeDescription());
            }

            @Override
            public String toString() {
                return "MethodRegistry.Handler.ForVisibilityBridge." + name();
            }

            /**
             * A compiled handler for a visibility bridge handler.
             */
            protected static class Compiled implements Handler.Compiled {

                /**
                 * The instrumented type.
                 */
                private final TypeDescription instrumentedType;

                /**
                 * Creates a new compiled handler for a visibility bridge.
                 *
                 * @param instrumentedType The instrumented type.
                 */
                protected Compiled(TypeDescription instrumentedType) {
                    this.instrumentedType = instrumentedType;
                }

                @Override
                public TypeWriter.MethodPool.Record assemble(MethodDescription methodDescription, MethodAttributeAppender attributeAppender) {
                    return TypeWriter.MethodPool.Record.ForDefinedMethod.OfVisibilityBridge.of(instrumentedType, methodDescription, attributeAppender);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && instrumentedType.equals(((Compiled) other).instrumentedType);
                }

                @Override
                public int hashCode() {
                    return instrumentedType.hashCode();
                }

                @Override
                public String toString() {
                    return "MethodRegistry.Handler.ForVisibilityBridge.Compiled{" +
                            "instrumentedType=" + instrumentedType +
                            '}';
                }
            }
        }
    }

    /**
     * A method registry that fully prepared the instrumented type.
     */
    interface Prepared {

        /**
         * Returns the fully prepared instrumented type.
         *
         * @return The fully prepared instrumented type.
         */
        TypeDescription getInstrumentedType();

        /**
         * Returns a list of all methods that should be instrumented.
         *
         * @return A list of all methods that should be instrumented.
         */
        MethodList<?> getInstrumentedMethods();

        /**
         * Returns the loaded type initializer of the instrumented type.
         *
         * @return The loaded type initializer of the instrumented type.
         */
        LoadedTypeInitializer getLoadedTypeInitializer();

        /**
         * The type initializer of the instrumented type.
         *
         * @return The type initializer of the instrumented type.
         */
        InstrumentedType.TypeInitializer getTypeInitializer();

        /**
         * Compiles this prepared method registry.
         *
         * @param implementationTargetFactory A factory for creating an implementation target.
         * @return A factory for creating an implementation target.
         */
        Compiled compile(Implementation.Target.Factory implementationTargetFactory);
    }

    /**
     * A compiled version of a method registry.
     */
    interface Compiled extends TypeWriter.MethodPool {

        /**
         * Returns the instrumented type that is to be created.
         *
         * @return The instrumented type that is to be created.
         */
        TypeDescription getInstrumentedType();

        /**
         * Returns a list of all methods that should be instrumented.
         *
         * @return A list of all methods that should be instrumented.
         */
        MethodList getInstrumentedMethods();

        /**
         * Returns the loaded type initializer of the instrumented type.
         *
         * @return The loaded type initializer of the instrumented type.
         */
        LoadedTypeInitializer getLoadedTypeInitializer();

        /**
         * The type initializer of the instrumented type.
         *
         * @return The type initializer of the instrumented type.
         */
        InstrumentedType.TypeInitializer getTypeInitializer();
    }

    /**
     * A default implementation of a method registry.
     */
    class Default implements MethodRegistry {

        /**
         * The list of currently registered entries in their application order.
         */
        private final List<Entry> entries;

        /**
         * Creates a new default method registry without entries.
         */
        public Default() {
            entries = Collections.emptyList();
        }

        /**
         * Creates a new default method registry.
         *
         * @param entries The currently registered entries.
         */
        private Default(List<Entry> entries) {
            this.entries = entries;
        }

        @Override
        public MethodRegistry prepend(LatentMethodMatcher methodMatcher,
                                      Handler handler,
                                      MethodAttributeAppender.Factory attributeAppenderFactory,
                                      MethodTransformer methodTransformer) {
            return new Default(join(new Entry(methodMatcher, handler, attributeAppenderFactory, methodTransformer), entries));
        }

        @Override
        public MethodRegistry append(LatentMethodMatcher methodMatcher,
                                     Handler handler,
                                     MethodAttributeAppender.Factory attributeAppenderFactory,
                                     MethodTransformer methodTransformer) {
            return new Default(join(entries, new Entry(methodMatcher, handler, attributeAppenderFactory, methodTransformer)));
        }

        @Override
        public MethodRegistry.Prepared prepare(InstrumentedType instrumentedType,
                                               MethodGraph.Compiler methodGraphCompiler,
                                               LatentMethodMatcher methodFilter) {
            LinkedHashMap<MethodDescription, Prepared.Entry> implementations = new LinkedHashMap<MethodDescription, Prepared.Entry>();
            Set<Handler> handlers = new HashSet<Handler>(entries.size());
            MethodList<?> helperMethods = instrumentedType.getDeclaredMethods();
            for (Entry entry : entries) {
                if (handlers.add(entry.getHandler())) {
                    instrumentedType = entry.getHandler().prepare(instrumentedType);
                    ElementMatcher<? super MethodDescription> handledMethods = noneOf(helperMethods);
                    helperMethods = instrumentedType.getDeclaredMethods();
                    for (MethodDescription helperMethod : helperMethods.filter(handledMethods)) {
                        implementations.put(helperMethod, entry.asPreparedEntry(helperMethod, Collections.<MethodDescription.TypeToken>emptySet()));
                    }
                }
            }
            MethodGraph.Linked methodGraph = methodGraphCompiler.compile(instrumentedType);
            ElementMatcher<? super MethodDescription> relevanceMatcher = not(anyOf(implementations.keySet())).and(methodFilter.resolve(instrumentedType));
            for (MethodGraph.Node node : methodGraph.listNodes()) {
                MethodDescription methodDescription = node.getRepresentative();
                boolean visibilityBridge = instrumentedType.isPublic() && !instrumentedType.isInterface();
                if (relevanceMatcher.matches(methodDescription)) {
                    for (Entry entry : entries) {
                        if (entry.resolve(instrumentedType).matches(methodDescription)) {
                            implementations.put(methodDescription, entry.asPreparedEntry(methodDescription, node.getBridges()));
                            visibilityBridge = false;
                            break;
                        }
                    }
                }
                if (visibilityBridge
                        && methodDescription.isPublic()
                        && !(methodDescription.isAbstract() || methodDescription.isFinal())
                        && !node.getSort().isMadeVisible()
                        && methodDescription.getDeclaringType().asRawType().isPackagePrivate()) {
                    // Visibility bridges are required for public types that inherit a public method from a package-private type.
                    // Checking the last condition contradicts any method that is defined by the instrumented type itself.
                    implementations.put(methodDescription, Prepared.Entry.forVisibilityBridge(methodDescription, node.getBridges()));
                }
            }
            MethodDescription typeInitializer = new MethodDescription.Latent.TypeInitializer(instrumentedType);
            for (Entry entry : entries) {
                if (entry.resolve(instrumentedType).matches(typeInitializer)) {
                    implementations.put(typeInitializer, entry.asPreparedEntry(typeInitializer, Collections.<MethodDescription.TypeToken>emptySet()));
                    break;
                }
            }
            return new Prepared(implementations,
                    instrumentedType.getLoadedTypeInitializer(),
                    instrumentedType.getTypeInitializer(),
                    instrumentedType.asRawType(),
                    methodGraph);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && entries.equals(((Default) other).entries);

        }

        @Override
        public int hashCode() {
            return entries.hashCode();
        }

        @Override
        public String toString() {
            return "MethodRegistry.Default{" +
                    "entries=" + entries +
                    '}';
        }

        /**
         * An entry of a default method registry.
         */
        protected static class Entry implements LatentMethodMatcher {

            /**
             * The latent method matcher that this entry represents.
             */
            private final LatentMethodMatcher methodMatcher;

            /**
             * The handler to apply to all matched entries.
             */
            private final Handler handler;

            /**
             * A method attribute appender factory to apply to all entries.
             */
            private final MethodAttributeAppender.Factory attributeAppenderFactory;

            private final MethodTransformer methodTransformer;

            /**
             * Creates a new entry.
             *
             * @param methodMatcher            The latent method matcher that this entry represents.
             * @param handler                  The handler to apply to all matched entries.
             * @param attributeAppenderFactory A method attribute appender factory to apply to all entries.
             */
            protected Entry(LatentMethodMatcher methodMatcher,
                            Handler handler,
                            MethodAttributeAppender.Factory attributeAppenderFactory,
                            MethodTransformer methodTransformer) {
                this.methodMatcher = methodMatcher;
                this.handler = handler;
                this.attributeAppenderFactory = attributeAppenderFactory;
                this.methodTransformer = methodTransformer;
            }

            /**
             * Transforms this entry into a prepared state.
             *
             * @param bridges The bridges to be appended to this entry.
             * @return A prepared version of this entry.
             */
            protected Prepared.Entry asPreparedEntry(MethodDescription methodDescription, Set<MethodDescription.TypeToken> bridges) {
                return new Prepared.Entry(handler, attributeAppenderFactory, methodTransformer.transform(methodDescription), bridges);
            }

            /**
             * Returns this entry's handler.
             *
             * @return The handler of this entry.
             */
            protected Handler getHandler() {
                return handler;
            }

            @Override
            public ElementMatcher<? super MethodDescription> resolve(TypeDescription instrumentedType) {
                return methodMatcher.resolve(instrumentedType);
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                Entry entry = (Entry) other;
                return methodMatcher.equals(entry.methodMatcher)
                        && handler.equals(entry.handler)
                        && attributeAppenderFactory.equals(entry.attributeAppenderFactory)
                        && methodTransformer.equals(entry.methodTransformer);
            }

            @Override
            public int hashCode() {
                int result = methodMatcher.hashCode();
                result = 31 * result + handler.hashCode();
                result = 31 * result + attributeAppenderFactory.hashCode();
                result = 31 * result + methodTransformer.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "MethodRegistry.Default.Entry{" +
                        "methodMatcher=" + methodMatcher +
                        ", handler=" + handler +
                        ", attributeAppenderFactory=" + attributeAppenderFactory +
                        ", methodTransformer=" + methodTransformer +
                        '}';
            }
        }

        /**
         * A prepared version of a default method registry.
         */
        protected static class Prepared implements MethodRegistry.Prepared {

            /**
             * A map of all method descriptions mapped to their handling entries.
             */
            private final LinkedHashMap<MethodDescription, Entry> implementations;

            /**
             * The loaded type initializer of the instrumented type.
             */
            private final LoadedTypeInitializer loadedTypeInitializer;

            /**
             * The type initializer of the instrumented type.
             */
            private final InstrumentedType.TypeInitializer typeInitializer;

            /**
             * The instrumented type.
             */
            private final TypeDescription instrumentedType;

            /**
             * A method graph describing the instrumented type.
             */
            private final MethodGraph.Linked methodGraph;

            /**
             * Creates a prepared version of a default method registry.
             *
             * @param implementations       A map of all method descriptions mapped to their handling entries.
             * @param loadedTypeInitializer The loaded type initializer of the instrumented type.
             * @param typeInitializer       The type initializer of the instrumented type.
             * @param instrumentedType      The instrumented type.
             * @param methodGraph           A method graph describing the instrumented type.
             */
            protected Prepared(LinkedHashMap<MethodDescription, Entry> implementations,
                               LoadedTypeInitializer loadedTypeInitializer,
                               InstrumentedType.TypeInitializer typeInitializer,
                               TypeDescription instrumentedType,
                               MethodGraph.Linked methodGraph) {
                this.implementations = implementations;
                this.loadedTypeInitializer = loadedTypeInitializer;
                this.typeInitializer = typeInitializer;
                this.instrumentedType = instrumentedType;
                this.methodGraph = methodGraph;
            }

            @Override
            public TypeDescription getInstrumentedType() {
                return instrumentedType;
            }

            @Override
            public LoadedTypeInitializer getLoadedTypeInitializer() {
                return loadedTypeInitializer;
            }

            @Override
            public InstrumentedType.TypeInitializer getTypeInitializer() {
                return typeInitializer;
            }

            @Override
            public MethodList<?> getInstrumentedMethods() {
                return new MethodList.Explicit<MethodDescription>(new ArrayList<MethodDescription>(implementations.keySet())).filter(not(isTypeInitializer()));
            }

            @Override
            public MethodRegistry.Compiled compile(Implementation.Target.Factory implementationTargetFactory) {
                Map<Handler, Handler.Compiled> compilationCache = new HashMap<Handler, Handler.Compiled>(implementations.size());
                Map<MethodAttributeAppender.Factory, MethodAttributeAppender> attributeAppenderCache = new HashMap<MethodAttributeAppender.Factory, MethodAttributeAppender>(implementations.size());
                LinkedHashMap<MethodDescription, Compiled.Entry> entries = new LinkedHashMap<MethodDescription, Compiled.Entry>(implementations.size());
                Implementation.Target implementationTarget = implementationTargetFactory.make(instrumentedType, methodGraph);
                for (Map.Entry<MethodDescription, Entry> entry : implementations.entrySet()) {
                    Handler.Compiled cachedHandler = compilationCache.get(entry.getValue().getHandler());
                    if (cachedHandler == null) {
                        cachedHandler = entry.getValue().getHandler().compile(implementationTarget);
                        compilationCache.put(entry.getValue().getHandler(), cachedHandler);
                    }
                    MethodAttributeAppender cachedAttributeAppender = attributeAppenderCache.get(entry.getValue().getAppenderFactory());
                    if (cachedAttributeAppender == null) {
                        cachedAttributeAppender = entry.getValue().getAppenderFactory().make(instrumentedType);
                        attributeAppenderCache.put(entry.getValue().getAppenderFactory(), cachedAttributeAppender);
                    }
                    entries.put(entry.getKey(), new Compiled.Entry(cachedHandler,
                            cachedAttributeAppender,
                            entry.getValue().getMethodDescription(),
                            entry.getValue().getBridges()));
                }
                return new Compiled(instrumentedType, loadedTypeInitializer, typeInitializer, entries);
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                Prepared prepared = (Prepared) other;
                return implementations.equals(prepared.implementations)
                        && loadedTypeInitializer.equals(prepared.loadedTypeInitializer)
                        && typeInitializer.equals(prepared.typeInitializer)
                        && instrumentedType.equals(prepared.instrumentedType)
                        && methodGraph.equals(prepared.methodGraph);
            }

            @Override
            public int hashCode() {
                int result = implementations.hashCode();
                result = 31 * result + loadedTypeInitializer.hashCode();
                result = 31 * result + typeInitializer.hashCode();
                result = 31 * result + instrumentedType.hashCode();
                result = 31 * result + methodGraph.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "MethodRegistry.Default.Prepared{" +
                        "implementations=" + implementations +
                        ", loadedTypeInitializer=" + loadedTypeInitializer +
                        ", typeInitializer=" + typeInitializer +
                        ", instrumentedType=" + instrumentedType +
                        ", methodGraph=" + methodGraph +
                        '}';
            }

            /**
             * An entry of a prepared method registry.
             */
            protected static class Entry {

                /**
                 * Creates an entry for a visibility bridge.
                 *
                 * @param bridgeTarget The bridge method's target.
                 * @param bridges      The type tokens describing all bridges.
                 * @return An entry representing a visibility bridge.
                 */
                protected static Entry forVisibilityBridge(MethodDescription bridgeTarget, Set<MethodDescription.TypeToken> bridges) {
                    return new Entry(Handler.ForVisibilityBridge.INSTANCE, new MethodAttributeAppender.ForMethod(bridgeTarget), bridgeTarget, bridges);
                }

                /**
                 * The handler for implementing methods.
                 */
                private final Handler handler;

                /**
                 * A attribute appender factory for appending attributes for any implemented method.
                 */
                private final MethodAttributeAppender.Factory attributeAppenderFactory;

                private final MethodDescription methodDescription;

                /**
                 * A set of bridges representing the bridge methods of this method.
                 */
                private final Set<MethodDescription.TypeToken> bridges;

                /**
                 * Creates a new prepared entry.
                 *
                 * @param handler                  The handler for implementing methods.
                 * @param attributeAppenderFactory A attribute appender factory for appending attributes for any implemented method.
                 * @param bridges                  A set of bridges representing the bridge methods of this method.
                 */
                protected Entry(Handler handler,
                                MethodAttributeAppender.Factory attributeAppenderFactory,
                                MethodDescription methodDescription,
                                Set<MethodDescription.TypeToken> bridges) {
                    this.handler = handler;
                    this.attributeAppenderFactory = attributeAppenderFactory;
                    this.methodDescription = methodDescription;
                    this.bridges = bridges;
                }

                /**
                 * Returns this entry's handler.
                 *
                 * @return The entry's handler.
                 */
                protected Handler getHandler() {
                    return handler;
                }

                /**
                 * Returns this entry's attribute appender factory.
                 *
                 * @return This entry's attribute appender factory.
                 */
                protected MethodAttributeAppender.Factory getAppenderFactory() {
                    return attributeAppenderFactory;
                }

                protected MethodDescription getMethodDescription() {
                    return methodDescription;
                }

                /**
                 * A set of bridges for the implemented method.
                 *
                 * @return A set of bridges for the implemented method.
                 */
                protected Set<MethodDescription.TypeToken> getBridges() {
                    return bridges;
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Entry entry = (Entry) other;
                    return handler.equals(entry.handler)
                            && attributeAppenderFactory.equals(entry.attributeAppenderFactory)
                            && methodDescription.equals(entry.methodDescription)
                            && bridges.equals(entry.bridges);
                }

                @Override
                public int hashCode() {
                    int result = handler.hashCode();
                    result = 31 * result + attributeAppenderFactory.hashCode();
                    result = 31 * result + methodDescription.hashCode();
                    result = 31 * result + bridges.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "MethodRegistry.Default.Prepared.Entry{" +
                            "handler=" + handler +
                            ", attributeAppenderFactory=" + attributeAppenderFactory +
                            ", methodDescription=" + methodDescription +
                            ", bridges=" + bridges +
                            '}';
                }
            }
        }

        /**
         * A compiled version of a default method registry.
         */
        protected static class Compiled implements MethodRegistry.Compiled {

            /**
             * The instrumented type.
             */
            private final TypeDescription instrumentedType;

            /**
             * The loaded type initializer of the instrumented type.
             */
            private final LoadedTypeInitializer loadedTypeInitializer;

            /**
             * The type initializer of the instrumented type.
             */
            private final InstrumentedType.TypeInitializer typeInitializer;

            /**
             * A map of all method descriptions mapped to their handling entries.
             */
            private final LinkedHashMap<MethodDescription, Entry> implementations;

            /**
             * Creates a new compiled version of a default method registry.
             *
             * @param instrumentedType      The instrumented type.
             * @param loadedTypeInitializer The loaded type initializer of the instrumented type.
             * @param typeInitializer       The type initializer of the instrumented type.
             * @param implementations       A map of all method descriptions mapped to their handling entries.
             */
            protected Compiled(TypeDescription instrumentedType,
                               LoadedTypeInitializer loadedTypeInitializer,
                               InstrumentedType.TypeInitializer typeInitializer,
                               LinkedHashMap<MethodDescription, Entry> implementations) {
                this.instrumentedType = instrumentedType;
                this.loadedTypeInitializer = loadedTypeInitializer;
                this.typeInitializer = typeInitializer;
                this.implementations = implementations;
            }

            @Override
            public TypeDescription getInstrumentedType() {
                return instrumentedType;
            }

            @Override
            public LoadedTypeInitializer getLoadedTypeInitializer() {
                return loadedTypeInitializer;
            }

            @Override
            public InstrumentedType.TypeInitializer getTypeInitializer() {
                return typeInitializer;
            }

            @Override
            public MethodList<?> getInstrumentedMethods() {
                return new MethodList.Explicit<MethodDescription>(new ArrayList<MethodDescription>(implementations.keySet())).filter(not(isTypeInitializer()));
            }

            @Override
            public Record target(MethodDescription methodDescription) {
                Entry entry = implementations.get(methodDescription);
                return entry == null
                        ? Record.ForNonDefinedMethod.INSTANCE
                        : entry.bind(instrumentedType);
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) return true;
                if (other == null || getClass() != other.getClass()) return false;
                Compiled compiled = (Compiled) other;
                return instrumentedType.equals(compiled.instrumentedType)
                        && loadedTypeInitializer.equals(compiled.loadedTypeInitializer)
                        && typeInitializer.equals(compiled.typeInitializer)
                        && implementations.equals(compiled.implementations);
            }

            @Override
            public int hashCode() {
                int result = instrumentedType.hashCode();
                result = 31 * result + loadedTypeInitializer.hashCode();
                result = 31 * result + typeInitializer.hashCode();
                result = 31 * result + implementations.hashCode();
                return result;
            }

            @Override
            public String toString() {
                return "MethodRegistry.Default.Compiled{" +
                        "instrumentedType=" + instrumentedType +
                        ", loadedTypeInitializer=" + loadedTypeInitializer +
                        ", typeInitializer=" + typeInitializer +
                        ", implementations=" + implementations +
                        '}';
            }

            protected static class Entry {

                private final Handler.Compiled handler;

                private final MethodAttributeAppender attributeAppender;

                private final MethodDescription methodDescription;

                private final Set<MethodDescription.TypeToken> bridges;

                public Entry(Handler.Compiled handler,
                             MethodAttributeAppender attributeAppender,
                             MethodDescription methodDescription,
                             Set<MethodDescription.TypeToken> bridges) {
                    this.handler = handler;
                    this.attributeAppender = attributeAppender;
                    this.methodDescription = methodDescription;
                    this.bridges = bridges;
                }

                protected Record bind(TypeDescription instrumentedType) {
                    return TypeWriter.MethodPool.Record.AccessBridgeWrapper.of(handler.assemble(methodDescription, attributeAppender),
                            instrumentedType,
                            methodDescription,
                            bridges,
                            attributeAppender);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Entry entry = (Entry) other;
                    return handler.equals(entry.handler)
                            && attributeAppender.equals(entry.attributeAppender)
                            && methodDescription.equals(entry.methodDescription)
                            && bridges.equals(entry.bridges);
                }

                @Override
                public int hashCode() {
                    int result = handler.hashCode();
                    result = 31 * result + attributeAppender.hashCode();
                    result = 31 * result + methodDescription.hashCode();
                    result = 31 * result + bridges.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "MethodRegistry.Default.Compiled.Entry{" +
                            "handler=" + handler +
                            ", attributeAppender=" + attributeAppender +
                            ", methodDescription=" + methodDescription +
                            ", bridges=" + bridges +
                            '}';
                }
            }
        }
    }
}
