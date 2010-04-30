/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006-2008 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2008 Thomas E Enebo <enebo@acm.org>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.compiler;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jruby.Ruby;
import org.jruby.RubyModule;
import org.jruby.MetaClass;
import org.jruby.RubyEncoding;
import org.jruby.RubyInstanceConfig;
import org.jruby.ast.ArgsNode;
import org.jruby.ast.Node;
import org.jruby.ast.executable.Script;
import org.jruby.ast.util.SexpMaker;
import org.jruby.compiler.impl.StandardASMCompiler;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.internal.runtime.methods.CallConfiguration;
import org.jruby.internal.runtime.methods.DefaultMethod;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.util.ClassCache;
import org.jruby.util.JavaNameMangler;
import org.jruby.util.SafePropertyAccessor;
import org.objectweb.asm.ClassReader;

public class JITCompiler implements JITCompilerMBean {
    public static final boolean USE_CACHE = true;
    public static final boolean DEBUG = SafePropertyAccessor.getBoolean("jruby.jit.debug", false);
    
    private AtomicLong compiledCount = new AtomicLong(0);
    private AtomicLong successCount = new AtomicLong(0);
    private AtomicLong failCount = new AtomicLong(0);
    private AtomicLong abandonCount = new AtomicLong(0);
    private AtomicLong compileTime = new AtomicLong(0);
    private AtomicLong averageCompileTime = new AtomicLong(0);
    private AtomicLong codeSize = new AtomicLong(0);
    private AtomicLong averageCodeSize = new AtomicLong(0);
    private AtomicLong largestCodeSize = new AtomicLong(0);
    
    public JITCompiler(Ruby ruby) {
        ruby.getBeanManager().register(this);
    }

    public DynamicMethod tryJIT(final DefaultMethod method, final ThreadContext context, final String name) {
        if (context.getRuntime().getInstanceConfig().getCompileMode().shouldJIT()) {
            return jitIsEnabled(method, context, name);
        }

        return null;
    }
    
    @Deprecated
    public void runJIT(final DefaultMethod method, final ThreadContext context, final String name) {
        // This method has JITed already or has been abandoned. Bail out.
        if (method.getCallCount() < 0) {
            return;
        } else {
            jitIsEnabled(method, context, name);
        }
    }

    private DynamicMethod jitIsEnabled(final DefaultMethod method, final ThreadContext context, final String name) {
        RubyInstanceConfig instanceConfig = context.getRuntime().getInstanceConfig();
        
        if (method.incrementCallCount() >= instanceConfig.getJitThreshold()) {
            return jitThresholdReached(method, instanceConfig, context, name);
        }

        return null;
    }
    
    private DynamicMethod jitThresholdReached(final DefaultMethod method, RubyInstanceConfig instanceConfig, final ThreadContext context, final String name) {
        try {
            // The cache is full. Abandon JIT for this method and bail out.
            ClassCache classCache = instanceConfig.getClassCache();
            if (classCache.isFull()) {
                abandonCount.incrementAndGet();
                method.setCallCount(-1);
                return null;
            }

            // Check if the method has been explicitly excluded
            String moduleName = method.getImplementationClass().getName();
            if(instanceConfig.getExcludedMethods().size() > 0) {
                String excludeModuleName = moduleName;
                if(method.getImplementationClass().isSingleton()) {
                    IRubyObject possibleRealClass = ((MetaClass)method.getImplementationClass()).getAttached();
                    if(possibleRealClass instanceof RubyModule) {
                        excludeModuleName = "Meta:" + ((RubyModule)possibleRealClass).getName();
                    }
                }

                if ((instanceConfig.getExcludedMethods().contains(excludeModuleName) ||
                     instanceConfig.getExcludedMethods().contains(excludeModuleName +"#"+name) ||
                     instanceConfig.getExcludedMethods().contains(name))) {
                    method.setCallCount(-1);
                    return null;
                }
            }

            String key = SexpMaker.create(name, method.getArgsNode(), method.getBodyNode());
            JITClassGenerator generator = new JITClassGenerator(name, key, context.getRuntime(), method, context);

            Class<Script> sourceClass = (Class<Script>)instanceConfig.getClassCache().cacheClassByKey(key, generator);

            if (sourceClass == null) {
                // class could not be found nor generated; give up on JIT and bail out
                failCount.incrementAndGet();
                method.setCallCount(-1);
                return null;
            }

            // successfully got back a jitted method
            successCount.incrementAndGet();

            // finally, grab the script
            Script jitCompiledScript = sourceClass.newInstance();

            // add to the jitted methods set
            Set<Script> jittedMethods = context.getRuntime().getJittedMethods();
            jittedMethods.add(jitCompiledScript);

            // logEvery n methods based on configuration
            if (instanceConfig.getJitLogEvery() > 0) {
                int methodCount = jittedMethods.size();
                if (methodCount % instanceConfig.getJitLogEvery() == 0) {
                    log(method, name, "live compiled methods: " + methodCount);
                }
            }

            if (instanceConfig.isJitLogging()) log(method, name, "done jitting");

            method.switchToJitted(jitCompiledScript, generator.callConfig());
            return null;
        } catch (Throwable t) {
            if (context.getRuntime().getDebug().isTrue()) t.printStackTrace();
            if (instanceConfig.isJitLoggingVerbose()) log(method, name, "could not compile", t.getMessage());

            failCount.incrementAndGet();
            method.setCallCount(-1);
            return null;
        }
    }

    public static String getHashForString(String str) {
        return getHashForBytes(RubyEncoding.encodeUTF8(str));
    }

    public static String getHashForBytes(byte[] bytes) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA1");
            sha1.update(bytes);
            byte[] digest = sha1.digest();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                builder.append(Integer.toString( ( digest[i] & 0xff ) + 0x100, 16).substring( 1 ));
            }
            return builder.toString().toUpperCase(Locale.ENGLISH);
        } catch (NoSuchAlgorithmException nsae) {
            throw new RuntimeException(nsae);
        }
    }

    public static void saveToCodeCache(Ruby ruby, byte[] bytecode, String packageName, File cachedClassFile) {
        String codeCache = RubyInstanceConfig.JIT_CODE_CACHE;
        File codeCacheDir = new File(codeCache);
        if (!codeCacheDir.exists()) {
            ruby.getWarnings().warn("jruby.jit.codeCache directory " + codeCacheDir + " does not exist");
        } else if (!codeCacheDir.isDirectory()) {
            ruby.getWarnings().warn("jruby.jit.codeCache directory " + codeCacheDir + " is not a directory");
        } else if (!codeCacheDir.canWrite()) {
            ruby.getWarnings().warn("jruby.jit.codeCache directory " + codeCacheDir + " is not writable");
        } else {
            if (!new File(codeCache, packageName).isDirectory()) {
                boolean createdDirs = new File(codeCache, packageName).mkdirs();
                if (!createdDirs) {
                    ruby.getWarnings().warn("could not create JIT cache dir: " + new File(codeCache, packageName));
                }
            }
            // write to code cache
            FileOutputStream fos = null;
            try {
                if (DEBUG) System.err.println("writing jitted code to to " + cachedClassFile);
                fos = new FileOutputStream(cachedClassFile);
                fos.write(bytecode);
            } catch (Exception e) {
                e.printStackTrace();
                // ignore
            } finally {
                try {fos.close();} catch (Exception e) {}
            }
        }
    }
    
    public class JITClassGenerator implements ClassCache.ClassGenerator {
        private StandardASMCompiler asmCompiler;
        private StaticScope staticScope;
        private Node bodyNode;
        private ArgsNode argsNode;
        private CallConfiguration jitCallConfig;
        private String digestString;
        
        private byte[] bytecode;
        private String name;
        private Ruby ruby;
        private String packageName;
        private String className;
        private String filename;
        private String methodName;
        
        public JITClassGenerator(String name, String key, Ruby ruby, DefaultMethod method, ThreadContext context) {
            this.packageName = "ruby/jit";
            this.digestString = getHashForString(key);
            this.className = packageName + "/" + JavaNameMangler.mangleStringForCleanJavaIdentifier(name) + "_" + digestString;
            this.name = className.replaceAll("/", ".");
            this.bodyNode = method.getBodyNode();
            this.argsNode = method.getArgsNode();
            this.methodName = name;
            filename = calculateFilename(argsNode, bodyNode);
            staticScope = method.getStaticScope();
            asmCompiler = new StandardASMCompiler(className, filename);
            this.ruby = ruby;
        }
        
        @SuppressWarnings("unchecked")
        protected void compile() {
            if (bytecode != null) return;
            
            // check if we have a cached compiled version on disk
            String codeCache = RubyInstanceConfig.JIT_CODE_CACHE;
            File cachedClassFile = new File(codeCache + "/" + className + ".class");

            if (codeCache != null &&
                    cachedClassFile.exists()) {
                FileInputStream fis = null;
                try {
                    if (DEBUG) System.err.println("loading cached code from: " + cachedClassFile);
                    fis = new FileInputStream(cachedClassFile);
                    bytecode = new byte[(int)fis.getChannel().size()];
                    fis.read(bytecode);
                    name = new ClassReader(bytecode).getClassName();
                    return;
                } catch (Exception e) {
                    // ignore and proceed to compile
                } finally {
                    try {fis.close();} catch (Exception e) {}
                }
            }
            
            // Time the compilation
            long start = System.nanoTime();

            asmCompiler = new StandardASMCompiler(className, filename);
            asmCompiler.startScript(staticScope);
            final ASTCompiler compiler = ruby.getInstanceConfig().newCompiler();

            CompilerCallback args = new CompilerCallback() {
                public void call(BodyCompiler context) {
                    compiler.compileArgs(argsNode, context, true);
                }
            };

            ASTInspector inspector = new ASTInspector();
            // check args first, since body inspection can depend on args
            inspector.inspect(argsNode);
            inspector.inspect(bodyNode);

            BodyCompiler methodCompiler;
            if (bodyNode != null) {
                // we have a body, do a full-on method
                methodCompiler = asmCompiler.startFileMethod(args, staticScope, inspector);
                compiler.compile(bodyNode, methodCompiler,true);
            } else {
                // If we don't have a body, check for required or opt args
                // if opt args, they could have side effects
                // if required args, need to raise errors if too few args passed
                // otherwise, method does nothing, make it a nop
                if (argsNode != null && (argsNode.getRequiredArgsCount() > 0 || argsNode.getOptionalArgsCount() > 0)) {
                    methodCompiler = asmCompiler.startFileMethod(args, staticScope, inspector);
                    methodCompiler.loadNil();
                } else {
                    methodCompiler = asmCompiler.startFileMethod(null, staticScope, inspector);
                    methodCompiler.loadNil();
                    jitCallConfig = CallConfiguration.FrameNoneScopeNone;
                }
            }
            methodCompiler.endBody();
            asmCompiler.endScript(false, false);
            
            // if we haven't already decided on a do-nothing call
            if (jitCallConfig == null) {
                jitCallConfig = inspector.getCallConfig();
            }
            
            bytecode = asmCompiler.getClassByteArray();
            
            if (bytecode.length > ruby.getInstanceConfig().getJitMaxSize()) {
                bytecode = null;
                throw new NotCompilableException(
                        "JITed method size exceeds configured max of " +
                        ruby.getInstanceConfig().getJitMaxSize());
            }

            if (codeCache != null) {
                JITCompiler.saveToCodeCache(ruby, bytecode, packageName, cachedClassFile);
            }
            
            compiledCount.incrementAndGet();
            compileTime.addAndGet(System.nanoTime() - start);
            codeSize.addAndGet(bytecode.length);
            averageCompileTime.set(compileTime.get() / compiledCount.get());
            averageCodeSize.set(codeSize.get() / compiledCount.get());
            synchronized (largestCodeSize) {
                if (largestCodeSize.get() < bytecode.length) {
                    largestCodeSize.set(bytecode.length);
                }
            }
        }

        public void generate() {
            compile();
        }
        
        public byte[] bytecode() {
            return bytecode;
        }

        public String name() {
            return name;
        }
        
        public CallConfiguration callConfig() {
            compile();
            return jitCallConfig;
        }

        public String toString() {
            return methodName + "() at " + bodyNode.getPosition().getFile() + ":" + bodyNode.getPosition().getLine();
        }
    }
    
    private static String calculateFilename(ArgsNode argsNode, Node bodyNode) {
        if (bodyNode != null) return bodyNode.getPosition().getFile();
        if (argsNode != null) return argsNode.getPosition().getFile();
        
        return "__eval__";
    }

    static void log(DefaultMethod method, String name, String message, String... reason) {
        String className = method.getImplementationClass().getBaseName();
        
        if (className == null) className = "<anon class>";

        System.err.print(message + ":" + className + "." + name + " at " + method.getPosition());
        
        if (reason.length > 0) {
            System.err.print(" because of: \"");
            for (int i = 0; i < reason.length; i++) {
                System.err.print(reason[i]);
            }
            System.err.print('"');
        }
        
        System.err.println("");
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getCompileCount() {
        return compiledCount.get();
    }

    public long getFailCount() {
        return failCount.get();
    }

    public long getCompileTime() {
        return compileTime.get() / 1000;
    }

    public long getAbandonCount() {
        return abandonCount.get();
    }
    
    public long getCodeSize() {
        return codeSize.get();
    }
    
    public long getAverageCodeSize() {
        return averageCodeSize.get();
    }
    
    public long getAverageCompileTime() {
        return averageCompileTime.get() / 1000;
    }
    
    public long getLargestCodeSize() {
        return largestCodeSize.get();
    }
}
