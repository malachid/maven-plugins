package com.goldin.plugins.kotlin

import static com.goldin.plugins.common.GMojoUtils.*
import com.goldin.plugins.common.BaseGroovyMojo3
import com.goldin.plugins.ivy.IvyMojo
import org.gcontracts.annotations.Requires
import org.jetbrains.jet.buildtools.core.BytecodeCompiler
import org.jetbrains.jet.cli.KDocLoader
import org.jetbrains.jet.compiler.CompilerPlugin
import org.jfrog.maven.annomojo.annotations.MojoParameter
import com.goldin.plugins.ivy.IvyHelper


/**
 * Kotlin mojo base class.
 */
abstract class KotlinBaseMojo extends BaseGroovyMojo3
{
    /**
     * Compilation source directory.
     */
    @MojoParameter ( required = false )
    public String src

    /**
     * Destination jar.
     */
    @MojoParameter ( required = false )
    public String jar

    /**
     * "stdlib" kotlinc argument (path to runtime.jar)
     */
    @MojoParameter ( required = false )
    public String stdlib

    /**
     * The output directory if KDoc documentation output is required
     */
    @MojoParameter ( required = false )
    public String docOutput

    /**
     * Kotlin compilation module, as alternative to source files or folders.
     */
    @MojoParameter ( required = false )
    public String module

    /**
     * "-includeRuntime" kotlinc argument, whether Kotlin runtime library is included in jar created.
     */
    @MojoParameter ( required = false )
    public boolean includeRuntime = false

    /**
     * Whether logging is verbose.
     */
    @MojoParameter ( required = false )
    public boolean verbose = false

    /**
     * Explicit Kotlin jars (alternative to Ivy download).
     */
    @MojoParameter ( required = false )
    public String[] kotlinJars


//    @Ensures({ result })
    abstract List<String> sources()

//    @Ensures({ result })
    abstract List<String> classpath()

//    @Ensures({ result })
    abstract String       output()


    @Override
    final void doExecute()
    {
        List<String> kotlinFiles = addKotlinDependency()*.path
        String[]     classpath   = ( kotlinFiles + classpath()).collect { new File( it ).canonicalPath }.toSet().toArray()
        List<String> sources     = sources()
        String       output      = output()

        assert sources && classpath && output && classpath.every { new File( it ).with { file || directory || mkdirs() }}

        if ( stdlib         ) { verify().file( new File( stdlib )) }
        if ( includeRuntime ) { assert ( module || jar ), "<includeRuntime> parameter can only be used with <module> source or <jar> destination" }

        final compiler = new BytecodeCompiler()

        if ( docOutput )
        {
            String docDir = docOutput + docOutputPostFix()
            log.info( "Generating API docs to [$docDir]" )

            /**
             * Add the KDocCompiler plugin.
             */
            CompilerPlugin plugin = new KDocLoader( docDir ).createCompilerPlugin()
            if ( plugin == null )
            {
                log.warn( "Could not load KDoc compiler plugin, did you add the kdoc jar to the plugin dependencies?" )
            }
            else
            {
                compiler.compilerPlugins.add( plugin )
            }
        }

        if ( module )
        {   /**
             * Compiling module.
             */

            verify().file( new File( module ))
            log.info(( jar     ? "Compiling [$module] => [$jar]" : "Compiling [$module]" ) +
                     ( verbose ? ", classpath = $classpath" : '' ))

            file().mkdirs( new File( jar ).parentFile )
            compiler.moduleToJar( module, jar, includeRuntime, stdlib, classpath )
        }
        else
        {   /**
             * Compiling sources.
             */

            final  sourceLocations  = ( src ? [ src ] : sources )
            final  destination      = ( jar ?: output )
            assert destination

            sourceLocations.each {
                String sourceLocation -> // May be a Kotlin file or sources directory

                verify().exists( new File( sourceLocation ))
                if ( ! jar ) { verify().directory( file().mkdirs( new File( output ))) }

                log.info(( "Compiling [$sourceLocation] => [$destination]" ) +
                         ( verbose ? ", classpath = $classpath" : '' ))

                if ( jar )
                {
                    file().mkdirs( new File( jar ).parentFile )
                    compiler.sourcesToJar( sourceLocation, jar, includeRuntime, stdlib, classpath )
                }
                else
                {
                    file().mkdirs( new File( output ))
                    compiler.sourcesToDir( sourceLocation, output, stdlib, classpath )
                }
            }
        }
    }


    String docOutputPostFix() { '' }


    /**
     * Adds Kotlin dependencies to plugin's classloader.
     */
    @Requires({ project && log && session && repoSystem })
    List<File> addKotlinDependency ()
    {
        List<File> files

        if ( kotlinJars )
        {
            files = kotlinJars.collect { new File( it ) }
        }
        else
        {
            URL     ivy     = this.class.classLoader.getResource( 'ivy.xml'     )
            URL     ivyconf = this.class.classLoader.getResource( 'ivyconf.xml' )
            files           = new IvyMojo().resolveArtifacts( ivy, null, new IvyHelper( ivyconf, verbose, true ))*.file
        }

        addToClassLoader(( URLClassLoader ) this.class.classLoader, files, verbose )
        files
    }
}
