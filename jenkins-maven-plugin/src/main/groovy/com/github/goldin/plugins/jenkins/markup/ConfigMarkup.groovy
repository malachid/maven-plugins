package com.github.goldin.plugins.jenkins.markup

import com.github.goldin.plugins.jenkins.Job
import com.github.goldin.plugins.jenkins.beans.ParameterType
import com.github.goldin.plugins.jenkins.beans.Trigger
import com.github.goldin.plugins.jenkins.beans.gerrit.TypePattern
import org.gcontracts.annotations.Requires


/**
 * Generates Jenkins config file XML markup.
 */
class ConfigMarkup extends Markup
{
    private final Job              job
    private final Map<String, Job> jobs
    private final String           timestamp
    private final String           indent
    private final String           newLine
    private final boolean          isMavenJob


    @Requires({ job && jobs && ( timestamp != null ) && indent && newLine })
    ConfigMarkup ( Job job, Map<String, Job> jobs, String timestamp, String indent, String newLine )
    {
        super( indent, newLine )

        this.job        = job
        this.jobs       = jobs
        this.timestamp  = timestamp
        this.indent     = indent
        this.newLine    = newLine
        this.isMavenJob = Job.JobType.maven.is( job.jobType )

        /**
         * Instances created by Maven that need to have their {@link com.github.goldin.plugins.jenkins.Task#builder} set.
         */

        assert this.builder

        job.tasks.            each { it.builder      = this.builder }
        job.prebuildersTasks. each { it.builder      = this.builder }
        job.postbuildersTasks.each { it.builder      = this.builder }
        job.groovys.          each { it.builder      = this.builder }
        job.parameters.       each { it.builder      = this.builder }
        if ( job.parameter ) { job.parameter.builder = this.builder }
        if ( job.groovy    ) { job.groovy.   builder = this.builder }
    }


    /**
     * Adds "extension point" markup
     * @param extensionPointMarkup custom extension point markup specified by user
     */
    private void addExtensionPoint( String extensionPointMarkup )
    {
        if ( extensionPointMarkup ) { add( "\n${ indent }${ extensionPointMarkup }\n${ indent }" ) }
    }


    /**
     * Builds Jenkins config XML markup using this object markup builder.
     */
    @Override
    void addMarkup ()
    {
        builder.with {

            mkp.xmlDeclaration( version: '1.0', encoding: 'UTF-8' )

            add( '<!-- ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ -->\n' )
            add( "<!-- Generated automatically by [${ job.generationPom }]${ timestamp } -->\n" )
            add( '<!-- ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ -->\n' )

            "${ isMavenJob ? 'maven2-moduleset' : 'project' }" {
                actions()
                addDescription()
                if ( job.displayName ){ displayName( job.displayName ) }
                if ( [ job.daysToKeep, job.numToKeep, job.artifactDaysToKeep, job.artifactNumToKeep ].any{ it > -1 } )
                {
                    logRotator {
                        daysToKeep( job.daysToKeep )
                        numToKeep( job.numToKeep )
                        artifactDaysToKeep( job.artifactDaysToKeep )
                        artifactNumToKeep( job.artifactNumToKeep )
                    }
                }
                keepDependencies( false )
                addProperties()
                addScm()
                add( 'quietPeriod',           job.quietPeriod )
                add( 'scmCheckoutRetryCount', job.scmCheckoutRetryCount )
                if(job.node) { assignedNode(job.node) }
                canRoam( job.node ? false : true )
                disabled( job.disabled )
                blockBuildWhenDownstreamBuilding( job.blockBuildWhenDownstreamBuilding )
                blockBuildWhenUpstreamBuilding( job.blockBuildWhenUpstreamBuilding )
                jdk( job.jdkName )
                add( 'authToken', job.authToken )
                addTriggers()
                concurrentBuild( false )
                if ( isMavenJob ){ addMaven() }
                else             { builders{ job.tasks*.addMarkup() }}
                addPublishers()
                buildWrappers{ addExtensionPoint( job.buildWrappers ) }
                if ( isMavenJob ){ addMavenBuilders() }
            }
            add( '\n' )
        }
    }


    /**
     * Adds config's {@code <description>} section to the {@link #builder}.
     */
    void addDescription ()
    {
        builder.description {
            add( """
<![CDATA[<center>
    <h4>
        Job definition is generated by <a href="${ job.generationPom }">Maven</a>
        using <a href="http://evgeny-goldin.com/wiki/Jenkins-maven-plugin">&quot;jenkins-maven-plugin&quot;</a> ${ timestamp ?: '' }.
        <br/>
        If you <a href="${ job.jenkinsUrl + '/job/' + job.id + '/configure' }">configure</a> this project manually -
        it will probably be <a href="${ job.generationPom }">overwritten</a>!
    </h4>
</center>
${ job.description }
<p/>
${ new DescriptionTableMarkup( job, jobs, indent, newLine ).markup }
]]>
 ${ indent }""" ) // Indentation correction: closing </description> tag is not positioned correctly due to String content injected
        }
    }


    /**
     * Adds {@code <properties>} section to the {@link #builder}.
     */
    void addProperties()
    {
        builder.properties {
            addExtensionPoint( job.properties )
            if ( job.parameters()) {
                'hudson.model.ParametersDefinitionProperty' {
                    parameterDefinitions {
                        job.parameters().findAll{ it.type != ParameterType.jira }*.addMarkup()
                    }
                }
                job.parameters().findAll{ it.type == ParameterType.jira }*.addMarkup()
            }
            if ( job.gitHubUrl ) { 'com.coravy.hudson.plugins.github.GithubProjectProperty' { projectUrl( job.gitHubUrl ) }}
        }
    }


    /**
     * Adds {@code <scm>} section to the {@link #builder}.
     */
    void addScm()
    {
        final scmBuilderClass = job.scmClass
        if  ( scmBuilderClass )
        {
            final scm         = scmBuilderClass.newInstance()
            scm.builder       = builder
            scm.job           = job
            scm.repositories  = job.repositories()
            scm.gerritTrigger = job.triggers().any { Trigger.GERRIT_TYPE == it.type }

            scm.addMarkup()
        }

        addExtensionPoint( job.scm )
    }


    /**
     * Adds {@code <triggers>} section to the {@link #builder}.
     */
    void addTriggers()
    {
        builder.triggers( class: 'vector' ) {
            for ( trigger in job.triggers())
            {
                "${ trigger.triggerClass }" {
                    if ( Trigger.GERRIT_TYPE == trigger.type )
                    {
                        addGerritTrigger( trigger )
                    }
                    else {
                        spec(( trigger.description ? "# ${ trigger.description }\n" : '' ) + trigger.expression )
                    }
                }
            }
        }
    }


    /**
     * Adds Gerrit trigger section to the {@link #builder}.
     */
    void addGerritTrigger( Trigger trigger )
    {
        assert trigger.type == Trigger.GERRIT_TYPE
        assert ( ! trigger.description ), "$job - Gerrit triggers are not using <description>, use <project> or <projects> instead"
        assert ( ! trigger.expression  ), "$job - Gerrit triggers are not using <expression>, use <project> or <projects> instead"
        assert trigger.projects(),        "$job - Gerrit triggers should have <project> or <projects> defined"

        final packagePrefix  = 'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data'
        final addTypePattern = { TypePattern tp               -> assert tp; builder.compareType( tp.type ); builder.pattern ( tp.pattern )}
        final addStringValue = { String value, String tagName -> assert tagName; if ( value ){ builder."$tagName"( value ) }}

        builder.with {
            spec()

            gerritProjects {
                for ( project in trigger.projects())
                {
                    "${ packagePrefix }.GerritProject" {
                        addTypePattern( project )

                        if ( project.branches())
                        {
                            branches {
                                for ( branch in project.branches())
                                {
                                    "${ packagePrefix }.Branch" { addTypePattern( branch )}
                                }
                            }
                        }

                        if ( project.filePaths())
                        {
                            filePaths {
                                for( filePath in project.filePaths())
                                {
                                    "${ packagePrefix }.FilePath" { addTypePattern( filePath )}
                                }
                            }
                        }
                    }
                }
            }

            addStringValue( trigger.verifyStarted,        'gerritBuildStartedVerifiedValue'      )
            addStringValue( trigger.codeReviewStarted,    'gerritBuildStartedCodeReviewValue'    )

            addStringValue( trigger.verifySuccessful,     'gerritBuildSuccessfulVerifiedValue'   )
            addStringValue( trigger.codeReviewSuccessful, 'gerritBuildSuccessfulCodeReviewValue' )

            addStringValue( trigger.verifyFailed,         'gerritBuildFailedVerifiedValue'       )
            addStringValue( trigger.codeReviewFailed,     'gerritBuildFailedCodeReviewValue'     )

            addStringValue( trigger.verifyUnstable,       'gerritBuildUnstableVerifiedValue'     )
            addStringValue( trigger.codeReviewUnstable,   'gerritBuildUnstableCodeReviewValue'   )

            silentMode               ( trigger.silentMode              )
            escapeQuotes             ( trigger.escapeQuotes            )

            buildStartMessage        ( trigger.buildStartMessage       )
            buildFailureMessage      ( trigger.buildFailureMessage     )
            buildSuccessfulMessage   ( trigger.buildSuccessfulMessage  )
            buildUnstableMessage     ( trigger.buildUnstableMessage    )
            buildUnsuccessfulFilepath( trigger.unsuccessfulMessageFile )

            customUrl                ( trigger.urlToPost               )
        }
    }


    /**
     * Adds Maven section to the {@link #builder}.
     */
    void addMaven()
    {
        assert isMavenJob

        builder.with {

            rootPOM( job.pom )
            goals{ add( job.mavenGoals ) }
            mavenName( job.mavenName )
            mavenOpts( job.mavenOpts ?: '' )
            aggregatorStyleBuild( true )
            incrementalBuild( job.incrementalBuild )

            if ( job.privateRepository || job.privateRepositoryPerExecutor )
            {
                localRepository( class: "hudson.maven.local_repo.${ job.privateRepository ? 'PerJobLocalRepositoryLocator' : 'PerExecutorLocalRepositoryLocator' }" )
                usePrivateRepository(true)
            }

            ignoreUpstremChanges( ! job.buildOnSNAPSHOT )
            archivingDisabled( job.archivingDisabled )
            resolveDependencies( false )
            processPlugins( false )
            mavenValidationLevel( 0 )
            runHeadless( false )

            reporters {
                addExtensionPoint( job.reporters )
                if ( job.mail.recipients )
                {
                    'hudson.maven.reporters.MavenMailer' {
                        recipients( job.mail.recipients )
                        dontNotifyEveryUnstableBuild( ! job.mail.sendForUnstable )
                        sendToIndividuals( job.mail.sendToIndividuals )
                    }
                }
            }
        }
    }


    /**
     * Adds Maven {@code <prebuilders>} and {@code <postbuilders>} to the {@link #builder}.
     */
    void addMavenBuilders()
    {
        assert isMavenJob

        builder.with {
            prebuilders {
                addExtensionPoint( job.prebuilders )
                job.groovys().findAll{ it.pre }*.addMarkup()
                job.prebuildersTasks*.addMarkup()
            }

            postbuilders {
                addExtensionPoint( job.postbuilders )
                job.groovys().findAll{ ! it.pre }*.addMarkup()
                job.postbuildersTasks*.addMarkup()
            }

            runPostStepsIfResult {
                name   ( job.runPostStepsIfResult.name    )
                ordinal( job.runPostStepsIfResult.ordinal )
                color  ( job.runPostStepsIfResult.color   )
            }
        }
    }


    /**
     * Adds {@code <publishers>} section to the {@link #builder}.
     */
    void addPublishers()
    {
        builder.publishers {
            addExtensionPoint( job.publishers )

            if (( ! isMavenJob ) && ( job.mail.recipients ))
            {
                'hudson.tasks.Mailer' {
                    recipients( job.mail.recipients )
                    dontNotifyEveryUnstableBuild( ! job.mail.sendForUnstable )
                    sendToIndividuals( job.mail.sendToIndividuals )
                }
            }

            if ( isMavenJob && job.deploy )
            {
                'hudson.maven.RedeployPublisher' {
                    if ( job.deploy.id  ) { id ( job.deploy.id  )}
                    if ( job.deploy.url ) { url( job.deploy.url )}
                    uniqueVersion ( job.deploy.uniqueVersion  )
                    evenIfUnstable( job.deploy.evenIfUnstable )
                }
            }

            if ( isMavenJob && job.artifactory.name )
            {
                'org.jfrog.hudson.ArtifactoryRedeployPublisher' {
                    details {
                        artifactoryName( job.artifactory.name )
                        repositoryKey( job.artifactory.repository )
                        snapshotsRepositoryKey( job.artifactory.snapshotsRepository )
                    }
                    deployArtifacts( job.artifactory.deployArtifacts )
                    username( job.artifactory.user )
                    scrambledPassword( job.artifactory.scrambledPassword )
                    includeEnvVars( job.artifactory.includeEnvVars )
                    skipBuildInfoDeploy( job.artifactory.skipBuildInfoDeploy )
                    evenIfUnstable( job.artifactory.evenIfUnstable )
                    runChecks( job.artifactory.runChecks )
                    violationRecipients( job.artifactory.violationRecipients )
                }
            }

            if ( job.invoke.jobs )
            {
                'hudson.plugins.parameterizedtrigger.BuildTrigger' {
                    configs {
                        'hudson.plugins.parameterizedtrigger.BuildTriggerConfig' {

                            final anyConfigs = ( job.invoke.currentBuildParams || job.invoke.subversionRevisionParam ||
                                                 job.invoke.gitCommitParam     || job.invoke.params                  ||
                                                 job.invoke.propertiesFileParams )

                            if ( ! anyConfigs )
                            {
                                configs( class: 'java.util.Collections$EmptyList' )
                            }
                            else
                            {
                                configs {
                                    if ( job.invoke.currentBuildParams      ){ 'hudson.plugins.parameterizedtrigger.CurrentBuildParameters'()}
                                    if ( job.invoke.subversionRevisionParam ){ 'hudson.plugins.parameterizedtrigger.SubversionRevisionBuildParameters'()}
                                    if ( job.invoke.gitCommitParam          ){ 'hudson.plugins.git.GitRevisionBuildParameters'()}

                                    if ( job.invoke.params )
                                    {
                                        'hudson.plugins.parameterizedtrigger.PredefinedBuildParameters' {
                                            builder.properties( job.invoke.params.readLines()*.trim().join( '\n' ))
                                        }
                                    }

                                    if ( job.invoke.propertiesFileParams )
                                    {
                                        'hudson.plugins.parameterizedtrigger.FileBuildParameters' {
                                            propertiesFile( job.invoke.propertiesFileParams )
                                        }
                                    }
                                }
                            }

                            projects( job.invoke.jobs )
                            condition( job.invoke.condition[ 0 ] )
                            triggerWithNoParameters( job.invoke.triggerWithoutParameters )
                        }
                    }
                }
            }
        }
    }
}
