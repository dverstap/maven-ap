package org.apache.maven.lifecycle;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.BuildFailureException;
import org.apache.maven.ConfigurationInterpolationException;
import org.apache.maven.ConfigurationInterpolator;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ReactorManager;
import org.apache.maven.extension.ExtensionManager;
import org.apache.maven.lifecycle.mapping.LifecycleMapping;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.ReportSet;
import org.apache.maven.monitor.event.EventDispatcher;
import org.apache.maven.monitor.event.MavenEvents;
import org.apache.maven.plugin.InvalidPluginException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.lifecycle.Execution;
import org.apache.maven.plugin.lifecycle.Phase;
import org.apache.maven.plugin.version.PluginVersionNotFoundException;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.apache.maven.project.interpolation.ModelInterpolationException;
import org.apache.maven.project.interpolation.ModelInterpolator;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.StringTokenizer;

/**
 * @author <a href="mailto:jason@maven.org">Jason van Zyl</a>
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @version $Id: DefaultLifecycleExecutor.java 799759 2009-07-31 22:19:49Z jdcasey $
 * @todo because of aggregation, we ended up with cli-ish stuff in here (like line() and the project logging, without much of the event handling)
 */
public class DefaultLifecycleExecutor
    extends AbstractLogEnabled
    implements LifecycleExecutor, Initializable, Contextualizable
{
    // ----------------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------------

    private PluginManager pluginManager;

    private ExtensionManager extensionManager;

    private List lifecycles;

    private ArtifactHandlerManager artifactHandlerManager;

    private List defaultReports;

    private Map phaseToLifecycleMap;

    private MavenProjectBuilder mavenProjectBuilder;

    private ModelInterpolator modelInterpolator;
    
    private ConfigurationInterpolator configInterpolator;

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    /**
     * Execute a task. Each task may be a phase in the lifecycle or the
     * execution of a mojo.
     *
     * @param session
     * @param rm
     * @param dispatcher
     */
    public void execute( final MavenSession session, final ReactorManager rm, final EventDispatcher dispatcher )
        throws BuildFailureException, LifecycleExecutionException
    {
        // TODO: This is dangerous, particularly when it's just a collection of loose-leaf projects being built
        // within the same reactor (using an inclusion pattern to gather them up)...
        MavenProject rootProject = rm.getTopLevelProject();

        List goals = session.getGoals();

        if ( goals.isEmpty() && rootProject != null )
        {
            String goal = rootProject.getDefaultGoal();

            if ( goal != null )
            {
                goals = Collections.singletonList( goal );
            }
        }

        if ( goals.isEmpty() )
        {
            StringBuffer buffer = new StringBuffer( 1024 );

            buffer.append( "\n\n" );
            buffer.append( "You must specify at least one goal or lifecycle phase to perform build steps.\n" );
            buffer.append( "The following list illustrates some commonly used build commands:\n\n" );
            buffer.append( "  mvn clean\n" );
            buffer.append( "    Deletes any build output (e.g. class files or JARs).\n" );
            buffer.append( "  mvn test\n" );
            buffer.append( "    Runs the unit tests for the project.\n" );
            buffer.append( "  mvn install\n" );
            buffer.append( "    Copies the project artifacts into your local repository.\n" );
            buffer.append( "  mvn deploy\n" );
            buffer.append( "    Copies the project artifacts into the remote repository.\n" );
            buffer.append( "  mvn site\n" );
            buffer.append( "    Creates project documentation (e.g. reports or Javadoc).\n\n" );
            buffer.append( "Please see\n" );
            buffer.append( "http://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html\n" );
            buffer.append( "for a complete description of available lifecycle phases.\n\n" );
            buffer.append( "Use \"mvn --help\" to show general usage information about Maven's command line.\n\n" );

            throw new BuildFailureException( buffer.toString() );
        }

        List taskSegments = segmentTaskListByAggregationNeeds( goals, session, rootProject );

        // TODO: probably don't want to do all this up front
        findExtensions( session );

        executeTaskSegments( taskSegments, rm, session, rootProject, dispatcher );
    }

    private void findExtensions( final MavenSession session )
        throws LifecycleExecutionException
    {
        // TODO: MNG-4081. What about extensions within the current reactor??
        Map<String, ArtifactHandler> handlers = new HashMap<String, ArtifactHandler>();
        for ( MavenProject project : session.getSortedProjects() )
        {
            for ( Iterator j = project.getBuildExtensions().iterator(); j.hasNext(); )
            {
                Extension extension = (Extension) j.next();
                try
                {
                    getLogger().debug( "Adding extension: " + extension );
                    extensionManager.addExtension( extension, project, session.getLocalRepository() );
                }
                catch ( PlexusContainerException e )
                {
                    throw new LifecycleExecutionException( "Unable to initialise extensions", e );
                }
                catch ( ArtifactResolutionException e )
                {
                    throw new LifecycleExecutionException( e.getMessage(), e );
                }
                catch ( ArtifactNotFoundException e )
                {
                    throw new LifecycleExecutionException( e.getMessage(), e );
                }
            }

        }
        
        extensionManager.registerWagons();
        
        handlers.putAll( extensionManager.getArtifactTypeHandlers() );

        for ( MavenProject project : session.getSortedProjects() )
        {
            try
            {
                handlers.putAll( findArtifactTypeHandlersInPlugins( project, session ) );

                // shudder...
                for ( ArtifactHandler handler : handlers.values() )
                {
                    if ( project.getPackaging().equals( handler.getPackaging() ) )
                    {
                        project.getArtifact().setArtifactHandler( handler );
                        
                        // NOTE: Adding this (maven 2.2.1) to short-circuit things. This means first match is used, NOT LAST.
                        break;
                    }
                }
            }
            catch ( PluginNotFoundException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
        }
        
        artifactHandlerManager.addHandlers( handlers );
    }

    private void executeTaskSegments( final List taskSegments, final ReactorManager rm, final MavenSession session,
                                      final MavenProject rootProject, final EventDispatcher dispatcher )
        throws LifecycleExecutionException, BuildFailureException
    {
        for ( Iterator it = taskSegments.iterator(); it.hasNext(); )
        {
            TaskSegment segment = (TaskSegment) it.next();

            if ( segment.aggregate() )
            {
                if ( !rm.isBlackListed( rootProject ) )
                {
                    line();

                    getLogger().info( "Building " + rootProject.getName() );

                    getLogger().info( "  " + segment );

                    line();

                    // !! This is ripe for refactoring to an aspect.
                    // Event monitoring.
                    String event = MavenEvents.PROJECT_EXECUTION;

                    long buildStartTime = System.currentTimeMillis();

                    String target = rootProject.getId() + " ( " + segment + " )";

                    dispatcher.dispatchStart( event, target );

                    try
                    {
                        session.setCurrentProject( rootProject );

                        // only call once, with the top-level project (assumed to be provided as a parameter)...
                        for ( Iterator goalIterator = segment.getTasks().iterator(); goalIterator.hasNext(); )
                        {
                            String task = (String) goalIterator.next();

                            executeGoalAndHandleFailures( task, session, rootProject, dispatcher, event, rm, buildStartTime,
                                                          target );
                        }

                        rm.registerBuildSuccess( rootProject, System.currentTimeMillis() - buildStartTime );

                    }
                    finally
                    {
                        session.setCurrentProject( null );
                    }

                    dispatcher.dispatchEnd( event, target );
                }
                else
                {
                    line();

                    getLogger().info( "SKIPPING " + rootProject.getName() );

                    getLogger().info( "  " + segment );

                    getLogger().info(
                        "This project has been banned from further executions due to previous failures." );

                    line();
                }
            }
            else
            {
                List sortedProjects = session.getSortedProjects();

                // iterate over projects, and execute on each...
                for ( Iterator projectIterator = sortedProjects.iterator(); projectIterator.hasNext(); )
                {
                    MavenProject currentProject = (MavenProject) projectIterator.next();

                    if ( !rm.isBlackListed( currentProject ) )
                    {
                        line();

                        getLogger().info( "Building " + currentProject.getName() );

                        getLogger().info( "  " + segment );

                        line();

                        // !! This is ripe for refactoring to an aspect.
                        // Event monitoring.
                        String event = MavenEvents.PROJECT_EXECUTION;

                        long buildStartTime = System.currentTimeMillis();

                        String target = currentProject.getId() + " ( " + segment + " )";
                        dispatcher.dispatchStart( event, target );

                        try
                        {
                            session.setCurrentProject( currentProject );

                            for ( Iterator goalIterator = segment.getTasks().iterator(); goalIterator.hasNext(); )
                            {
                                String task = (String) goalIterator.next();

                                executeGoalAndHandleFailures( task, session, currentProject, dispatcher, event, rm,
                                                              buildStartTime, target );
                            }

                        }
                        finally
                        {
                            session.setCurrentProject( null );
                        }

                        rm.registerBuildSuccess( currentProject, System.currentTimeMillis() - buildStartTime );

                        dispatcher.dispatchEnd( event, target );
                    }
                    else
                    {
                        line();

                        getLogger().info( "SKIPPING " + currentProject.getName() );

                        getLogger().info( "  " + segment );

                        getLogger().info(
                            "This project has been banned from further executions due to previous failures." );

                        line();
                    }
                }
            }
        }
    }

    private void executeGoalAndHandleFailures( final String task, final MavenSession session, final MavenProject project,
                                               final EventDispatcher dispatcher, final String event, final ReactorManager rm,
                                               final long buildStartTime, final String target )
        throws BuildFailureException, LifecycleExecutionException
    {
        try
        {
            executeGoal( task, session, project );
        }
        catch ( LifecycleExecutionException e )
        {
            dispatcher.dispatchError( event, target, e );

            if ( handleExecutionFailure( rm, project, e, task, buildStartTime ) )
            {
                throw e;
            }
        }
        catch ( BuildFailureException e )
        {
            dispatcher.dispatchError( event, target, e );

            if ( handleExecutionFailure( rm, project, e, task, buildStartTime ) )
            {
                throw e;
            }
        }
    }

    private boolean handleExecutionFailure( final ReactorManager rm, final MavenProject project, final Exception e, final String task,
                                            final long buildStartTime )
    {
        rm.registerBuildFailure( project, e, task, System.currentTimeMillis() - buildStartTime );

        if ( ReactorManager.FAIL_FAST.equals( rm.getFailureBehavior() ) )
        {
            return true;
        }
        else if ( ReactorManager.FAIL_AT_END.equals( rm.getFailureBehavior() ) )
        {
            rm.blackList( project );
        }
        // if NEVER, don't blacklist
        return false;
    }

    private List segmentTaskListByAggregationNeeds( final List tasks, final MavenSession session, final MavenProject project )
        throws LifecycleExecutionException, BuildFailureException
    {
        List segments = new ArrayList();

        if ( project != null )
        {

            TaskSegment currentSegment = null;
            for ( Iterator it = tasks.iterator(); it.hasNext(); )
            {
                String task = (String) it.next();

                // if it's a phase, then we don't need to check whether it's an aggregator.
                // simply add it to the current task partition.
                if ( getPhaseToLifecycleMap().containsKey( task ) )
                {
                    if ( currentSegment != null && currentSegment.aggregate() )
                    {
                        segments.add( currentSegment );
                        currentSegment = null;
                    }

                    if ( currentSegment == null )
                    {
                        currentSegment = new TaskSegment();
                    }

                    currentSegment.add( task );
                }
                else
                {
                    MojoDescriptor mojo = null;
                    try
                    {
                        // definitely a CLI goal, can use prefix
                        mojo = getMojoDescriptor( task, session, project, task, true, false );
                    }
                    catch ( PluginNotFoundException e )
                    {
                        // TODO: shouldn't hit this, investigate using the same resolution logic as otheres for plugins in the reactor
                        getLogger().info(
                            "Cannot find mojo descriptor for: \'" + task + "\' - Treating as non-aggregator." );
                        getLogger().debug( "", e );
                    }

                    // if the mojo descriptor was found, determine aggregator status according to:
                    // 1. whether the mojo declares itself an aggregator
                    // 2. whether the mojo DOES NOT require a project to function (implicitly avoid reactor)
                    if ( mojo != null && ( mojo.isAggregator() || !mojo.isProjectRequired() ) )
                    {
                        if ( currentSegment != null && !currentSegment.aggregate() )
                        {
                            segments.add( currentSegment );
                            currentSegment = null;
                        }

                        if ( currentSegment == null )
                        {
                            currentSegment = new TaskSegment( true );
                        }

                        currentSegment.add( task );
                    }
                    else
                    {
                        if ( currentSegment != null && currentSegment.aggregate() )
                        {
                            segments.add( currentSegment );
                            currentSegment = null;
                        }

                        if ( currentSegment == null )
                        {
                            currentSegment = new TaskSegment();
                        }

                        currentSegment.add( task );
                    }
                }
            }

            segments.add( currentSegment );
        }
        else
        {
            TaskSegment segment = new TaskSegment( false );
            for ( Iterator i = tasks.iterator(); i.hasNext(); )
            {
                segment.add( (String) i.next() );
            }
            segments.add( segment );
        }

        return segments;
    }

    private void executeGoal( final String task, final MavenSession session, final MavenProject project )
        throws LifecycleExecutionException, BuildFailureException
    {
        try
        {
            Stack forkEntryPoints = new Stack();
            if ( getPhaseToLifecycleMap().containsKey( task ) )
            {
                Lifecycle lifecycle = getLifecycleForPhase( task );

                // we have a lifecycle phase, so lets bind all the necessary goals
                Map lifecycleMappings = constructLifecycleMappings( session, task, project, lifecycle );
                executeGoalWithLifecycle( task, forkEntryPoints, session, lifecycleMappings, project, lifecycle );
            }
            else
            {
                executeStandaloneGoal( task, forkEntryPoints, session, project );
            }
        }
        catch ( PluginNotFoundException e )
        {
            throw new BuildFailureException( "A required plugin was not found: " + e.getMessage(), e );
        }
    }

    private void executeGoalWithLifecycle( final String task, final Stack forkEntryPoints, final MavenSession session,
                                           final Map lifecycleMappings, final MavenProject project, final Lifecycle lifecycle )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        List goals = processGoalChain( task, lifecycleMappings, lifecycle );

        if ( !goals.isEmpty() )
        {
            executeGoals( goals, forkEntryPoints, session, project );
        }
        else
        {
            getLogger().info( "No goals needed for project - skipping" );
        }
    }

    private void executeStandaloneGoal( final String task, final Stack forkEntryPoints, final MavenSession session, final MavenProject project )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        // guaranteed to come from the CLI and not be part of a phase
        MojoDescriptor mojoDescriptor = getMojoDescriptor( task, session, project, task, true, false );
        executeGoals( Collections.singletonList( new MojoExecution( mojoDescriptor, MojoExecution.CLI_EXECUTION_ID ) ),
                      forkEntryPoints, session, project );
    }

    private void executeGoals( final List goals, final Stack forkEntryPoints, final MavenSession session, final MavenProject project )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        for ( Iterator i = goals.iterator(); i.hasNext(); )
        {
            MojoExecution mojoExecution = (MojoExecution) i.next();

            MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
            
            PlexusConfiguration configuration = mojoDescriptor.getMojoConfiguration();
            boolean usesReactorProjects = mojoDescriptor.isAggregator() || usesSessionOrReactorProjects( configuration );
            
            if ( usesReactorProjects )
            {
                calculateAllConcreteStates( session );
            }
            else
            {
                calculateConcreteState( project, session, true );
            }
            
            calculateConcreteConfiguration( mojoExecution, project, session );
            
            List reportExecutions = null;
            if ( mojoDescriptor.isRequiresReports() )
            {
                reportExecutions = getReportExecutions( project, forkEntryPoints, mojoExecution, session );
            }
            
            boolean hasFork = false;
            if ( mojoDescriptor.getExecutePhase() != null || mojoDescriptor.getExecuteGoal() != null )
            {
                hasFork = true;
            }
            else if ( reportExecutions != null )
            {
                for ( Iterator it = reportExecutions.iterator(); it.hasNext(); )
                {
                    MojoExecution reportExecution = (MojoExecution) it.next();
                    MojoDescriptor reportDescriptor = reportExecution.getMojoDescriptor();
                    if ( reportDescriptor.getExecutePhase() != null || reportDescriptor.getExecuteGoal() != null )
                    {
                        hasFork = true;
                    }
                }
            }
            
            if ( hasFork )
            {
                // NOTE: This must always happen, regardless of treatment of reactorProjects below, because
                // if we're in a forked execution right now, the current project will itself be an execution project of
                // something in the reactorProjects list, and won't have a next-stage executionProject created
                // for it otherwise, which leaves the project == null for the upcoming forked execution.
                createExecutionProject( project, session, true );
                
                if ( usesReactorProjects )
                {
                    List reactorProjects = session.getSortedProjects();
                    for ( Iterator it = reactorProjects.iterator(); it.hasNext(); )
                    {
                        MavenProject reactorProject = (MavenProject) it.next();
                        createExecutionProject( reactorProject, session, false );
                    }
                }
            }

            if ( mojoDescriptor.getExecutePhase() != null || mojoDescriptor.getExecuteGoal() != null )
            {
                forkEntryPoints.push( mojoDescriptor );

                forkLifecycle( mojoDescriptor, forkEntryPoints, session, project, mojoExecution.getExecutionId() );

                forkEntryPoints.pop();
            }
            
            if ( mojoDescriptor.isRequiresReports() )
            {
                List reports = getReports( reportExecutions, project, mojoExecution, session );

                mojoExecution.setReports( reports );

                for ( Iterator j = mojoExecution.getForkedExecutions().iterator(); j.hasNext(); )
                {
                    MojoExecution forkedExecution = (MojoExecution) j.next();
                    MojoDescriptor descriptor = forkedExecution.getMojoDescriptor();

                    if ( descriptor.getExecutePhase() != null )
                    {
                        forkEntryPoints.push( descriptor );

                        forkLifecycle( descriptor, forkEntryPoints, session, project, forkedExecution.getExecutionId() );

                        forkEntryPoints.pop();
                    }
                }
            }
            
            if ( hasFork )
            {
                // NOTE: This must always happen, regardless of treatment of reactorProjects below, because
                // if we're in a forked execution right now, the current project will itself be an execution project of
                // something in the reactorProjects list, and may not have had its own executionProject instance reset to 
                // a concrete state again after the previous forked executions.
                calculateConcreteState( project.getExecutionProject(), session, true );
                
                // FIXME: Would be nice to find a way to cause the execution project to stay in a concrete state...
                // TODO: Test this! It should be fixed, but I don't want to destabilize until I have the issue I'm working on fixed.
                if ( usesReactorProjects )
                {
                    calculateAllConcreteStates( session );
                    List reactorProjects = session.getSortedProjects();
                    for ( Iterator it = reactorProjects.iterator(); it.hasNext(); )
                    {
                        MavenProject reactorProject = (MavenProject) it.next();
                        calculateConcreteState( reactorProject.getExecutionProject(), session, false );
                    }
                }
            }

            try
            {
                pluginManager.executeMojo( project, mojoExecution, session );
            }
            catch ( PluginManagerException e )
            {
                throw new LifecycleExecutionException( "Internal error in the plugin manager executing goal '" +
                    mojoDescriptor.getId() + "': " + e.getMessage(), e );
            }
            catch ( ArtifactNotFoundException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
            catch ( InvalidDependencyVersionException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
            catch ( ArtifactResolutionException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
            catch ( MojoFailureException e )
            {
                throw new BuildFailureException( e.getMessage(), e );
            }
            catch ( MojoExecutionException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
            catch ( PluginConfigurationException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
        }
    }
    
    private void createExecutionProject( final MavenProject project, final MavenSession session, final boolean processProjectReferences )
        throws LifecycleExecutionException
    {
        MavenProject executionProject = new MavenProject( project );
        
        calculateConcreteState( executionProject, session, processProjectReferences );
        
        project.setExecutionProject( executionProject );
    }

    private boolean usesSessionOrReactorProjects( final PlexusConfiguration configuration )
    {
        String value = configuration != null ? String.valueOf( configuration ) : null;
        
        if ( value != null )
        {
            if ( value.indexOf( "${session" ) > -1 || value.indexOf( "${reactorProjects}" ) > -1 )
            {
                return true;
            }
        }
        
        return false;
    }

    private void calculateConcreteConfiguration( final MojoExecution mojoExecution, final MavenProject project, final MavenSession session )
        throws LifecycleExecutionException
    {
        if ( mojoExecution.getConfiguration() == null )
        {
            return;
        }
        
        try
        {
            mojoExecution.setConfiguration( (Xpp3Dom) configInterpolator.interpolate(
                                                                                      mojoExecution.getConfiguration(),
                                                                                      project,
                                                                                      session.getProjectBuilderConfiguration() ) );
        }
        catch ( ConfigurationInterpolationException e )
        {
            throw new LifecycleExecutionException( "Error interpolating configuration for: '" + mojoExecution.getMojoDescriptor().getRoleHint() +
                                                   "' (execution: '" + mojoExecution.getExecutionId() + "')", e );
        }
    }
    
    private void calculateAllConcreteStates( final MavenSession session )
        throws LifecycleExecutionException
    {
        List projects = session.getSortedProjects();
        if ( projects != null )
        {
            for ( Iterator it = projects.iterator(); it.hasNext(); )
            {
                calculateConcreteState( (MavenProject) it.next(), session, false );
            }
        }
    }

    private void calculateConcreteState( final MavenProject project, final MavenSession session, final boolean processReferences )
        throws LifecycleExecutionException
    {
        if ( mavenProjectBuilder != null && project != null )
        {
            try
            {
                mavenProjectBuilder.calculateConcreteState( project, session.getProjectBuilderConfiguration(), processReferences );
            }
            catch ( ModelInterpolationException e )
            {
                throw new LifecycleExecutionException( "Failed to calculate concrete state for project: " + project,
                                                         e );
            }
        }
    }
    
//    private void restoreAllDynamicStates( MavenSession session )
//        throws LifecycleExecutionException
//    {
//        List reactorProjects = session.getSortedProjects();
//        if ( reactorProjects != null )
//        {
//            for ( Iterator it = reactorProjects.iterator(); it.hasNext(); )
//            {
//                MavenProject project = (MavenProject) it.next();
//                restoreDynamicState( project, session, false );
//            }
//        }
//    }
//
//    private void restoreDynamicState( MavenProject project, MavenSession session, boolean processReferences )
//        throws LifecycleExecutionException
//    {
//        try
//        {
//            mavenProjectBuilder.restoreDynamicState( project, session.getProjectBuilderConfiguration(), processReferences );
//        }
//        catch ( ModelInterpolationException e )
//        {
//            throw new LifecycleExecutionException( "Failed to restore dynamic state for project: " + project, e );
//        }
//    }

    private List getReportExecutions( final MavenProject project, final Stack forkEntryPoints, final MojoExecution mojoExecution, final MavenSession session )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        List reportPlugins = project.getReportPlugins();

        if ( project.getModel().getReports() != null )
        {
            getLogger().error(
                "Plugin contains a <reports/> section: this is IGNORED - please use <reporting/> instead." );
        }

        if ( project.getReporting() == null || !project.getReporting().isExcludeDefaults() )
        {
            if ( reportPlugins == null )
            {
                reportPlugins = new ArrayList();
            }
            else
            {
                reportPlugins = new ArrayList( reportPlugins );
            }

            for ( Iterator i = defaultReports.iterator(); i.hasNext(); )
            {
                String report = (String) i.next();

                StringTokenizer tok = new StringTokenizer( report, ":" );
                int count = tok.countTokens();
                if ( count != 2 && count != 3 )
                {
                    getLogger().warn( "Invalid default report ignored: '" + report + "' (must be groupId:artifactId[:version])" );
                }
                else
                {
                    String groupId = tok.nextToken();
                    String artifactId = tok.nextToken();
                    String version = tok.hasMoreTokens() ? tok.nextToken() : null;

                    boolean found = false;
                    for ( Iterator j = reportPlugins.iterator(); j.hasNext() && !found; )
                    {
                        ReportPlugin reportPlugin = (ReportPlugin) j.next();
                        if ( reportPlugin.getGroupId().equals( groupId ) &&
                            reportPlugin.getArtifactId().equals( artifactId ) )
                        {
                            found = true;
                        }
                    }

                    if ( !found )
                    {
                        ReportPlugin reportPlugin = new ReportPlugin();
                        reportPlugin.setGroupId( groupId );
                        reportPlugin.setArtifactId( artifactId );
                        reportPlugin.setVersion( version );
                        reportPlugins.add( reportPlugin );
                    }
                }
            }
        }

        List reports = new ArrayList();
        if ( reportPlugins != null )
        {
            for ( Iterator it = reportPlugins.iterator(); it.hasNext(); )
            {
                ReportPlugin reportPlugin = (ReportPlugin) it.next();

                List reportSets = reportPlugin.getReportSets();

                if ( reportSets == null || reportSets.isEmpty() )
                {
                    reports.addAll( getReportExecutions( reportPlugin, forkEntryPoints, null, project, session, mojoExecution ) );
                }
                else
                {
                    for ( Iterator j = reportSets.iterator(); j.hasNext(); )
                    {
                        ReportSet reportSet = (ReportSet) j.next();

                        reports.addAll( getReportExecutions( reportPlugin, forkEntryPoints, reportSet, project, session, mojoExecution ) );
                    }
                }
            }
        }
        return reports;
    }
    
    private List getReportExecutions( final ReportPlugin reportPlugin,
                             final Stack forkEntryPoints,
                             final ReportSet reportSet,
                             final MavenProject project,
                             final MavenSession session,
                             final MojoExecution mojoExecution )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        PluginDescriptor pluginDescriptor = verifyReportPlugin( reportPlugin, project, session );

        List reports = new ArrayList();
        for ( Iterator i = pluginDescriptor.getMojos().iterator(); i.hasNext(); )
        {
            MojoDescriptor mojoDescriptor = (MojoDescriptor) i.next();
            if ( forkEntryPoints.contains( mojoDescriptor ) )
            {
                getLogger().debug( "Omitting report: " + mojoDescriptor.getFullGoalName() + " from reports list. It initiated part of the fork currently executing." );
                continue;
            }

            // TODO: check ID is correct for reports
            // if the POM configured no reports, give all from plugin
            if ( reportSet == null || reportSet.getReports().contains( mojoDescriptor.getGoal() ) )
            {
                String id = null;
                if ( reportSet != null )
                {
                    id = reportSet.getId();
                }
                else
                {
                    id = mojoExecution.getExecutionId();
                }
                
                MojoExecution reportExecution;
                if ( id.startsWith( MojoExecution.DEFAULT_EXEC_ID_PREFIX ) )
                {
                    reportExecution = new MojoExecution( mojoDescriptor );
                }
                else
                {
                    reportExecution = new MojoExecution( mojoDescriptor, id );
                }

                reports.add( reportExecution );
            }
        }
        return reports;
    }

    private List getReports( final List reportExecutions, final MavenProject project, final MojoExecution mojoExecution, final MavenSession session )
        throws LifecycleExecutionException
    {
        List reports = new ArrayList();
        
        for ( Iterator it = reportExecutions.iterator(); it.hasNext(); )
        {
            MojoExecution reportExecution = (MojoExecution) it.next();
            PluginDescriptor pluginDescriptor = reportExecution.getMojoDescriptor().getPluginDescriptor();
            
            try
            {
                MavenReport reportMojo = pluginManager.getReport( project, reportExecution, session );

                // Comes back null if it was a plugin, not a report - these are mojos in the reporting plugins that are not reports
                if ( reportMojo != null )
                {
                    reports.add( reportMojo );
                    mojoExecution.addMojoExecution( reportExecution );
                }
            }
            catch ( PluginManagerException e )
            {
                throw new LifecycleExecutionException(
                    "Error getting reports from the plugin '" + pluginDescriptor.getId() + "': " + e.getMessage(), e );
            }
            catch ( PluginConfigurationException e )
            {
                throw new LifecycleExecutionException(
                    "Error getting reports from the plugin '" + pluginDescriptor.getId() + "'", e );
            }
            catch ( ArtifactNotFoundException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
            catch ( ArtifactResolutionException e )
            {
                throw new LifecycleExecutionException( e.getMessage(), e );
            }
        }
        
        return reports;
    }

    private void forkLifecycle( final MojoDescriptor mojoDescriptor, final Stack ancestorLifecycleForkers, final MavenSession session,
                                final MavenProject project, final String executionId )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();
        getLogger().info( "Preparing " + pluginDescriptor.getGoalPrefix() + ":" + mojoDescriptor.getGoal() );

        if ( mojoDescriptor.isAggregator() )
        {
            for ( Iterator i = session.getSortedProjects().iterator(); i.hasNext(); )
            {
                MavenProject reactorProject = (MavenProject) i.next();
                
                line();

                getLogger().info( "Building " + reactorProject.getName() );

                line();

                forkProjectLifecycle( mojoDescriptor, ancestorLifecycleForkers, session, reactorProject, executionId );
            }
        }
        else
        {
            forkProjectLifecycle( mojoDescriptor, ancestorLifecycleForkers, session, project, executionId );
        }
    }

    private void forkProjectLifecycle( final MojoDescriptor mojoDescriptor, final Stack forkEntryPoints, final MavenSession session,
                                       MavenProject project, final String executionId )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        project = project.getExecutionProject();
        
        forkEntryPoints.push( mojoDescriptor );

        PluginDescriptor pluginDescriptor = mojoDescriptor.getPluginDescriptor();

        String targetPhase = mojoDescriptor.getExecutePhase();

        Map lifecycleMappings = null;
        if ( targetPhase != null )
        {
            Lifecycle lifecycle = getLifecycleForPhase( targetPhase );

            // Create new lifecycle
            lifecycleMappings = constructLifecycleMappings( session, targetPhase, project, lifecycle );

            String executeLifecycle = mojoDescriptor.getExecuteLifecycle();
            if ( executeLifecycle != null )
            {
                org.apache.maven.plugin.lifecycle.Lifecycle lifecycleOverlay;
                try
                {
                    lifecycleOverlay = pluginDescriptor.getLifecycleMapping( executeLifecycle );
                }
                catch ( IOException e )
                {
                    throw new LifecycleExecutionException( "Unable to read lifecycle mapping file: " + e.getMessage(),
                                                           e );
                }
                catch ( XmlPullParserException e )
                {
                    throw new LifecycleExecutionException( "Unable to parse lifecycle mapping file: " + e.getMessage(),
                                                           e );
                }

                if ( lifecycleOverlay == null )
                {
                    throw new LifecycleExecutionException( "Lifecycle '" + executeLifecycle + "' not found in plugin" );
                }

                for ( Iterator i = lifecycleOverlay.getPhases().iterator(); i.hasNext(); )
                {
                    Phase phase = (Phase) i.next();
                    for ( Iterator j = phase.getExecutions().iterator(); j.hasNext(); )
                    {
                        Execution exec = (Execution) j.next();

                        for ( Iterator k = exec.getGoals().iterator(); k.hasNext(); )
                        {
                            String goal = (String) k.next();

                            PluginDescriptor lifecyclePluginDescriptor;
                            String lifecycleGoal;

                            // Here we are looking to see if we have a mojo from an external plugin.
                            // If we do then we need to lookup the plugin descriptor for the externally
                            // referenced plugin so that we can overly the execution into the lifecycle.
                            // An example of this is the corbertura plugin that needs to call the surefire
                            // plugin in forking mode.
                            //
                            //<phase>
                            //  <id>test</id>
                            //  <executions>
                            //    <execution>
                            //      <goals>
                            //        <goal>org.apache.maven.plugins:maven-surefire-plugin:test</goal>
                            //      </goals>
                            //      <configuration>
                            //        <classesDirectory>${project.build.directory}/generated-classes/cobertura</classesDirectory>
                            //        <ignoreFailures>true</ignoreFailures>
                            //        <forkMode>once</forkMode>
                            //      </configuration>
                            //    </execution>
                            //  </executions>
                            //</phase>

                            // ----------------------------------------------------------------------
                            //
                            // ----------------------------------------------------------------------

                            if ( goal.indexOf( ":" ) > 0 )
                            {
                                String[] s = StringUtils.split( goal, ":" );

                                String groupId = s[0];
                                String artifactId = s[1];
                                lifecycleGoal = s[2];

                                Plugin plugin = new Plugin();
                                plugin.setGroupId( groupId );
                                plugin.setArtifactId( artifactId );
                                lifecyclePluginDescriptor = verifyPlugin( plugin, project, session );
                                if ( lifecyclePluginDescriptor == null )
                                {
                                    throw new LifecycleExecutionException(
                                        "Unable to find plugin " + groupId + ":" + artifactId );
                                }
                            }
                            else
                            {
                                lifecyclePluginDescriptor = pluginDescriptor;
                                lifecycleGoal = goal;
                            }

                            Xpp3Dom configuration = (Xpp3Dom) exec.getConfiguration();
                            // NOTE: This seems to be duplicated below. Why??
                            if ( phase.getConfiguration() != null )
                            {
                                configuration = Xpp3Dom.mergeXpp3Dom( new Xpp3Dom( (Xpp3Dom) phase.getConfiguration() ),
                                                                      configuration );
                            }

                            MojoDescriptor desc = getMojoDescriptor( lifecyclePluginDescriptor, lifecycleGoal );
                            MojoExecution mojoExecution;
                            if ( executionId.startsWith( MojoExecution.DEFAULT_EXEC_ID_PREFIX ) )
                            {
                                mojoExecution = new MojoExecution( desc, configuration );
                            }
                            else
                            {
                                mojoExecution = new MojoExecution( desc, configuration, executionId );
                            }
                            
                            addToLifecycleMappings( lifecycleMappings, phase.getId(), mojoExecution,
                                                    session.getSettings() );
                        }
                    }

                    if ( phase.getConfiguration() != null )
                    {
                        // Merge in general configuration for a phase.
                        // TODO: this is all kind of backwards from the POMM. Let's align it all under 2.1.
                        //   We should create a new lifecycle executor for modelVersion >5.0.0
                        for ( Iterator j = lifecycleMappings.values().iterator(); j.hasNext(); )
                        {
                            List tasks = (List) j.next();

                            for ( Iterator k = tasks.iterator(); k.hasNext(); )
                            {
                                MojoExecution exec = (MojoExecution) k.next();

                                Xpp3Dom configuration = Xpp3Dom.mergeXpp3Dom(
                                    new Xpp3Dom( (Xpp3Dom) phase.getConfiguration() ), exec.getConfiguration() );

                                exec.setConfiguration( configuration );
                            }
                        }
                    }

                }
            }

            removeFromLifecycle( forkEntryPoints, lifecycleMappings );
        }

        if ( targetPhase != null )
        {
            Lifecycle lifecycle = getLifecycleForPhase( targetPhase );

            executeGoalWithLifecycle( targetPhase, forkEntryPoints, session, lifecycleMappings, project, lifecycle );
        }
        else
        {
            String goal = mojoDescriptor.getExecuteGoal();
            MojoDescriptor desc = getMojoDescriptor( pluginDescriptor, goal );
            executeGoals( Collections.singletonList( new MojoExecution( desc, goal ) ), forkEntryPoints, session, project );
        }
    }

    private Lifecycle getLifecycleForPhase( final String phase )
        throws BuildFailureException, LifecycleExecutionException
    {
        Lifecycle lifecycle = (Lifecycle) getPhaseToLifecycleMap().get( phase );

        if ( lifecycle == null )
        {
            throw new BuildFailureException( "Unable to find lifecycle for phase '" + phase + "'" );
        }
        return lifecycle;
    }

    private MojoDescriptor getMojoDescriptor( final PluginDescriptor pluginDescriptor, final String goal )
        throws LifecycleExecutionException
    {
        MojoDescriptor desc = pluginDescriptor.getMojo( goal );

        if ( desc == null )
        {
            String message =
                "Required goal '" + goal + "' not found in plugin '" + pluginDescriptor.getGoalPrefix() + "'";
            int index = goal.indexOf( ':' );
            if ( index >= 0 )
            {
                String prefix = goal.substring( index + 1 );
                if ( prefix.equals( pluginDescriptor.getGoalPrefix() ) )
                {
                    message = message + " (goals should not be prefixed - try '" + prefix + "')";
                }
            }
            throw new LifecycleExecutionException( message );
        }
        return desc;
    }

    private void removeFromLifecycle( final Stack lifecycleForkers, final Map lifecycleMappings )
    {
        for ( Iterator lifecycleIterator = lifecycleMappings.values().iterator(); lifecycleIterator.hasNext(); )
        {
            List tasks = (List) lifecycleIterator.next();

            for ( Iterator taskIterator = tasks.iterator(); taskIterator.hasNext(); )
            {
                MojoExecution execution = (MojoExecution) taskIterator.next();

                if ( lifecycleForkers.contains( execution.getMojoDescriptor() ) )
                {
                    taskIterator.remove();
                    getLogger().warn( "Removing: " + execution.getMojoDescriptor().getGoal()
                                      + " from forked lifecycle, to prevent recursive invocation." );
                }
            }
        }
    }

    private Map constructLifecycleMappings( final MavenSession session, final String selectedPhase, final MavenProject project,
                                            final Lifecycle lifecycle )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        // first, bind those associated with the packaging
        Map lifecycleMappings = bindLifecycleForPackaging( session, selectedPhase, project, lifecycle );

        // next, loop over plugins and for any that have a phase, bind it
        for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext(); )
        {
            Plugin plugin = (Plugin) i.next();

            bindPluginToLifecycle( plugin, session, lifecycleMappings, project );
        }

        return lifecycleMappings;
    }

    private Map bindLifecycleForPackaging( final MavenSession session, final String selectedPhase, final MavenProject project,
                                           final Lifecycle lifecycle )
        throws LifecycleExecutionException, BuildFailureException, PluginNotFoundException
    {
        Map mappings = findMappingsForLifecycle( session, project, lifecycle );

        List optionalMojos = findOptionalMojosForLifecycle( session, project, lifecycle );

        Map lifecycleMappings = new HashMap();

        for ( Iterator i = lifecycle.getPhases().iterator(); i.hasNext(); )
        {
            String phase = (String) i.next();

            String phaseTasks = (String) mappings.get( phase );

            if ( phaseTasks != null )
            {
                for ( StringTokenizer tok = new StringTokenizer( phaseTasks, "," ); tok.hasMoreTokens(); )
                {
                    String goal = tok.nextToken().trim();

                    // Not from the CLI, don't use prefix
                    MojoDescriptor mojoDescriptor = getMojoDescriptor( goal, session, project, selectedPhase, false,
                                                                       optionalMojos.contains( goal ) );

                    if ( mojoDescriptor == null )
                    {
                        continue;
                    }

                    if ( mojoDescriptor.isDirectInvocationOnly() )
                    {
                        throw new LifecycleExecutionException( "Mojo: \'" + goal +
                            "\' requires direct invocation. It cannot be used as part of lifecycle: \'" +
                            project.getPackaging() + "\'." );
                    }

                    addToLifecycleMappings( lifecycleMappings, phase, new MojoExecution( mojoDescriptor ),
                                            session.getSettings() );
                }
            }

            if ( phase.equals( selectedPhase ) )
            {
                break;
            }
        }

        return lifecycleMappings;
    }

    private Map findMappingsForLifecycle( final MavenSession session, final MavenProject project, final Lifecycle lifecycle )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        String packaging = project.getPackaging();
        Map mappings = null;

        LifecycleMapping m = (LifecycleMapping) findExtension( project, LifecycleMapping.ROLE, packaging, session );
        if ( m != null )
        {
            mappings = m.getPhases( lifecycle.getId() );
        }

        Map defaultMappings = lifecycle.getDefaultPhases();

        if ( mappings == null )
        {
            try
            {
                m = (LifecycleMapping) session.lookup( LifecycleMapping.ROLE, packaging );
                mappings = m.getPhases( lifecycle.getId() );
            }
            catch ( ComponentLookupException e )
            {
                if ( defaultMappings == null )
                {
                    throw new LifecycleExecutionException(
                        "Cannot find lifecycle mapping for packaging: \'" + packaging + "\'.", e );
                }
            }
        }

        if ( mappings == null )
        {
            if ( defaultMappings == null )
            {
                throw new LifecycleExecutionException(
                    "Cannot find lifecycle mapping for packaging: \'" + packaging + "\', and there is no default" );
            }
            else
            {
                mappings = defaultMappings;
            }
        }

        return mappings;
    }

    private List findOptionalMojosForLifecycle( final MavenSession session, final MavenProject project, final Lifecycle lifecycle )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        String packaging = project.getPackaging();
        List optionalMojos = null;

        LifecycleMapping m = (LifecycleMapping) findExtension( project, LifecycleMapping.ROLE, packaging, session );

        if ( m != null )
        {
            optionalMojos = m.getOptionalMojos( lifecycle.getId() );
        }

        if ( optionalMojos == null )
        {
            try
            {
                m = (LifecycleMapping) session.lookup( LifecycleMapping.ROLE, packaging );
                optionalMojos = m.getOptionalMojos( lifecycle.getId() );
            }
            catch ( ComponentLookupException e )
            {
                getLogger().debug( "Error looking up lifecycle mapping to retrieve optional mojos. Lifecycle ID: " +
                    lifecycle.getId() + ". Error: " + e.getMessage(), e );
            }
        }

        if ( optionalMojos == null )
        {
            optionalMojos = Collections.EMPTY_LIST;
        }

        return optionalMojos;
    }

    private Object findExtension( final MavenProject project, final String role, final String roleHint, final MavenSession session )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        Object pluginComponent = null;

        for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext() && pluginComponent == null; )
        {
            Plugin plugin = (Plugin) i.next();

            if ( plugin.isExtensions() )
            {
                loadPluginFully( plugin, project, session );

                // TODO: if moved to the plugin manager we already have the descriptor from above and so do can lookup the container directly
                try
                {
                    pluginComponent = pluginManager.getPluginComponent( plugin, role, roleHint );
                }
                catch ( ComponentLookupException e )
                {
                    getLogger().debug( "Unable to find the lifecycle component in the extension", e );
                }
                catch ( PluginManagerException e )
                {
                    throw new LifecycleExecutionException(
                        "Error getting extensions from the plugin '" + plugin.getKey() + "': " + e.getMessage(), e );
                }
            }
        }
        return pluginComponent;
    }

    /**
     * @todo Not particularly happy about this. Would like WagonManager and ArtifactTypeHandlerManager to be able to
     * lookup directly, or have them passed in
     */
    private Map<String, ArtifactHandler> findArtifactTypeHandlersInPlugins( final MavenProject project, final MavenSession session )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        Map<String, ArtifactHandler> map = new HashMap<String, ArtifactHandler>();
        for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext(); )
        {
            Plugin plugin = (Plugin) i.next();

            if ( plugin.isExtensions() )
            {
                loadPluginFully( plugin, project, session );

                // TODO: if moved to the plugin manager we already have the descriptor from above and so do can lookup the container directly
                try
                {
                    Map components = pluginManager.getPluginComponents( plugin, ArtifactHandler.ROLE );
                    map.putAll( components );
                }
                catch ( ComponentLookupException e )
                {
                    getLogger().debug( "Unable to find the lifecycle component in the extension", e );
                }
                catch ( PluginManagerException e )
                {
                    throw new LifecycleExecutionException( "Error looking up available components from plugin '" +
                        plugin.getKey() + "': " + e.getMessage(), e );
                }
            }
        }
        return map;
    }

    /**
     * Take each mojo contained with a plugin, look to see whether it contributes to a
     * phase in the lifecycle and if it does place it at the end of the list of goals
     * to execute for that given phase.
     *
     * @param project
     * @param session
     */
    private void bindPluginToLifecycle( final Plugin plugin, final MavenSession session, final Map phaseMap, final MavenProject project )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        Settings settings = session.getSettings();

        PluginDescriptor pluginDescriptor =
            verifyPlugin( plugin, project, session );

        if ( pluginDescriptor.getMojos() != null && !pluginDescriptor.getMojos().isEmpty() )
        {
            // use the plugin if inherit was true in a base class, or it is in the current POM, otherwise use the default inheritence setting
            if ( plugin.isInheritanceApplied() || pluginDescriptor.isInheritedByDefault() )
            {
                if ( plugin.getGoals() != null )
                {
                    getLogger().error(
                        "Plugin contains a <goals/> section: this is IGNORED - please use <executions/> instead." );
                }

                List executions = plugin.getExecutions();

                if ( executions != null )
                {
                    for ( Iterator it = executions.iterator(); it.hasNext(); )
                    {
                        PluginExecution execution = (PluginExecution) it.next();

                        bindExecutionToLifecycle( pluginDescriptor, phaseMap, execution, settings );
                    }
                }
            }
        }
    }

    private PluginDescriptor verifyPlugin( final Plugin plugin, final MavenProject project, final MavenSession session )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        PluginDescriptor pluginDescriptor;
        try
        {
            // TODO: MNG-4081...need to flush this plugin once we look at it, to avoid using an external
            // version of a plugin when a newer version will be created in the current reactor...
            pluginDescriptor = pluginManager.loadPluginDescriptor( plugin, project, session );
        }
        catch ( PluginManagerException e )
        {
            throw new LifecycleExecutionException(
                "Internal error in the plugin manager getting plugin '" + plugin.getKey() + "': " + e.getMessage(), e );
        }
        catch ( PluginVersionResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidPluginException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( PluginVersionNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        return pluginDescriptor;
    }

    private PluginDescriptor loadPluginFully( final Plugin plugin, final MavenProject project, final MavenSession session )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        PluginDescriptor pluginDescriptor;
        try
        {
            // TODO: MNG-4081...need to flush this plugin once we look at it, to avoid using an external
            // version of a plugin when a newer version will be created in the current reactor...
            pluginDescriptor = pluginManager.loadPluginFully( plugin, project, session );
        }
        catch ( PluginManagerException e )
        {
            throw new LifecycleExecutionException(
                "Internal error in the plugin manager getting plugin '" + plugin.getKey() + "': " + e.getMessage(), e );
        }
        catch ( PluginVersionResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidPluginException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( PluginVersionNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        return pluginDescriptor;
    }

    private PluginDescriptor verifyReportPlugin( final ReportPlugin plugin, final MavenProject project, final MavenSession session )
        throws LifecycleExecutionException, PluginNotFoundException
    {
        PluginDescriptor pluginDescriptor;
        try
        {
            pluginDescriptor = pluginManager.verifyReportPlugin( plugin, project, session );
        }
        catch ( PluginManagerException e )
        {
            throw new LifecycleExecutionException(
                "Internal error in the plugin manager getting report '" + plugin.getKey() + "': " + e.getMessage(), e );
        }
        catch ( PluginVersionResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( InvalidPluginException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        catch ( PluginVersionNotFoundException e )
        {
            throw new LifecycleExecutionException( e.getMessage(), e );
        }
        return pluginDescriptor;
    }

    private void bindExecutionToLifecycle( final PluginDescriptor pluginDescriptor, final Map phaseMap, final PluginExecution execution,
                                           final Settings settings )
        throws LifecycleExecutionException
    {
        for ( Iterator i = execution.getGoals().iterator(); i.hasNext(); )
        {
            String goal = (String) i.next();

            MojoDescriptor mojoDescriptor = pluginDescriptor.getMojo( goal );
            if ( mojoDescriptor == null )
            {
                throw new LifecycleExecutionException(
                    "'" + goal + "' was specified in an execution, but not found in the plugin" );
            }

            // We have to check to see that the inheritance rules have been applied before binding this mojo.
            if ( execution.isInheritanceApplied() || mojoDescriptor.isInheritedByDefault() )
            {
                MojoExecution mojoExecution = new MojoExecution( mojoDescriptor, execution.getId() );

                String phase = execution.getPhase();

                if ( phase == null )
                {
                    // if the phase was not in the configuration, use the phase in the descriptor
                    phase = mojoDescriptor.getPhase();
                }

                if ( phase != null )
                {
                    if ( mojoDescriptor.isDirectInvocationOnly() )
                    {
                        throw new LifecycleExecutionException( "Mojo: \'" + goal +
                            "\' requires direct invocation. It cannot be used as part of the lifecycle (it was included via the POM)." );
                    }

                    addToLifecycleMappings( phaseMap, phase, mojoExecution, settings );
                }
            }
        }
    }

    private void addToLifecycleMappings( final Map lifecycleMappings, final String phase, final MojoExecution mojoExecution,
                                         final Settings settings )
    {
        List goals = (List) lifecycleMappings.get( phase );

        if ( goals == null )
        {
            goals = new ArrayList();
            lifecycleMappings.put( phase, goals );
        }

        MojoDescriptor mojoDescriptor = mojoExecution.getMojoDescriptor();
        if ( settings.isOffline() && mojoDescriptor.isOnlineRequired() )
        {
            String goal = mojoDescriptor.getGoal();
            getLogger().warn( goal + " requires online mode, but maven is currently offline. Disabling " + goal + "." );
        }
        else
        {
            goals.add( mojoExecution );
        }
    }

    private List processGoalChain( final String task, final Map phaseMap, final Lifecycle lifecycle )
    {
        List goals = new ArrayList();

        // only execute up to the given phase
        int index = lifecycle.getPhases().indexOf( task );

        for ( int i = 0; i <= index; i++ )
        {
            String p = (String) lifecycle.getPhases().get( i );

            List phaseGoals = (List) phaseMap.get( p );

            if ( phaseGoals != null )
            {
                goals.addAll( phaseGoals );
            }
        }
        return goals;
    }

    private MojoDescriptor getMojoDescriptor( final String task, final MavenSession session, final MavenProject project,
                                              final String invokedVia, final boolean canUsePrefix, final boolean isOptionalMojo )
        throws BuildFailureException, LifecycleExecutionException, PluginNotFoundException
    {
        String goal;
        Plugin plugin = null;

        PluginDescriptor pluginDescriptor = null;

        try
        {
            StringTokenizer tok = new StringTokenizer( task, ":" );
            int numTokens = tok.countTokens();

            if ( numTokens == 2 )
            {
                if ( !canUsePrefix )
                {
                    String msg = "Mapped-prefix lookup of mojos are only supported from direct invocation. " +
                        "Please use specification of the form groupId:artifactId[:version]:goal instead. " +
                        "(Offending mojo: \'" + task + "\', invoked via: \'" + invokedVia + "\')";
                    throw new LifecycleExecutionException( msg );
                }

                String prefix = tok.nextToken();
                goal = tok.nextToken();

                // Steps for retrieving the plugin model instance:
                // 1. request directly from the plugin collector by prefix
                pluginDescriptor = pluginManager.getPluginDescriptorForPrefix( prefix );
                if ( pluginDescriptor != null )
                {
                    plugin = new Plugin();
                    plugin.setGroupId( pluginDescriptor.getGroupId() );
                    plugin.setArtifactId( pluginDescriptor.getArtifactId() );
                    plugin.setVersion( pluginDescriptor.getVersion() );
                }

                // 2. search plugins in the current POM
                if ( plugin == null )
                {
                    for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext(); )
                    {
                        Plugin buildPlugin = (Plugin) i.next();

                        PluginDescriptor desc =
                            verifyPlugin( buildPlugin, project, session );
                        if ( prefix.equals( desc.getGoalPrefix() ) )
                        {
                            plugin = buildPlugin;
                            pluginDescriptor = desc;
                            break;
                        }
                    }
                }

                // 3. look in the repository via search groups
                if ( plugin == null )
                {
                    plugin = pluginManager.getPluginDefinitionForPrefix( prefix, session, project );
                }

                // 4. default to o.a.m.plugins and maven-<prefix>-plugin
                if ( plugin == null )
                {
                    plugin = new Plugin();
                    plugin.setGroupId( PluginDescriptor.getDefaultPluginGroupId() );
                    plugin.setArtifactId( PluginDescriptor.getDefaultPluginArtifactId( prefix ) );
                }
            }
            else if ( numTokens == 3 || numTokens == 4 )
            {
                plugin = new Plugin();

                plugin.setGroupId( tok.nextToken() );
                plugin.setArtifactId( tok.nextToken() );

                if ( numTokens == 4 )
                {
                    plugin.setVersion( tok.nextToken() );
                }

                goal = tok.nextToken();
            }
            else
            {
                String message = "Invalid task '" + task + "': you must specify a valid lifecycle phase, or" +
                    " a goal in the format plugin:goal or pluginGroupId:pluginArtifactId:pluginVersion:goal";
                throw new BuildFailureException( message );
            }

            if ( plugin.getVersion() == null )
            {
                for ( Iterator i = project.getBuildPlugins().iterator(); i.hasNext(); )
                {
                    Plugin buildPlugin = (Plugin) i.next();

                    if ( buildPlugin.getKey().equals( plugin.getKey() ) )
                    {
                        plugin = buildPlugin;
                        break;
                    }
                }

                project.injectPluginManagementInfo( plugin );
            }

            if ( pluginDescriptor == null )
            {
                pluginDescriptor = verifyPlugin( plugin, project, session );
            }

            // this has been simplified from the old code that injected the plugin management stuff, since
            // pluginManagement injection is now handled by the project method.
            project.addPlugin( plugin );

            MojoDescriptor mojoDescriptor = pluginDescriptor.getMojo( goal );
            if ( mojoDescriptor == null )
            {
                if ( isOptionalMojo )
                {
                    getLogger().info( "Skipping missing optional mojo: " + task );
                }
                else
                {
                    throw new BuildFailureException( "Required goal not found: " + task + " in "
                        + pluginDescriptor.getId() );
                }
            }

            return mojoDescriptor;
        }
        catch ( PluginNotFoundException e )
        {
            if ( isOptionalMojo )
            {
                getLogger().info( "Skipping missing optional mojo: " + task );
                getLogger().debug( "Mojo: " + task + " could not be found. Reason: " + e.getMessage(), e );
            }
            else
            {
                throw e;
            }
        }

        return null;
    }

    protected void line()
    {
        getLogger().info( "------------------------------------------------------------------------" );
    }

    public Map getPhaseToLifecycleMap()
        throws LifecycleExecutionException
    {
        if ( phaseToLifecycleMap == null )
        {
            phaseToLifecycleMap = new HashMap();

            for ( Iterator i = lifecycles.iterator(); i.hasNext(); )
            {
                Lifecycle lifecycle = (Lifecycle) i.next();

                for ( Iterator p = lifecycle.getPhases().iterator(); p.hasNext(); )
                {
                    String phase = (String) p.next();

                    if ( phaseToLifecycleMap.containsKey( phase ) )
                    {
                        Lifecycle prevLifecycle = (Lifecycle) phaseToLifecycleMap.get( phase );
                        throw new LifecycleExecutionException( "Phase '" + phase +
                            "' is defined in more than one lifecycle: '" + lifecycle.getId() + "' and '" +
                            prevLifecycle.getId() + "'" );
                    }
                    else
                    {
                        phaseToLifecycleMap.put( phase, lifecycle );
                    }
                }
            }
        }
        return phaseToLifecycleMap;
    }

    private static class TaskSegment
    {
        private boolean aggregate;

        private final List tasks = new ArrayList();

        TaskSegment()
        {

        }

        TaskSegment( final boolean aggregate )
        {
            this.aggregate = aggregate;
        }

        public String toString()
        {
            StringBuffer message = new StringBuffer();

            message.append( " task-segment: [" );

            for ( Iterator it = tasks.iterator(); it.hasNext(); )
            {
                String task = (String) it.next();

                message.append( task );

                if ( it.hasNext() )
                {
                    message.append( ", " );
                }
            }

            message.append( "]" );

            if ( aggregate )
            {
                message.append( " (aggregator-style)" );
            }

            return message.toString();
        }

        boolean aggregate()
        {
            return aggregate;
        }

        void add( final String task )
        {
            tasks.add( task );
        }

        List getTasks()
        {
            return tasks;
        }
    }
    
    public List getLifecycles()
    {
        return lifecycles;
    }

    // -------------------------------------------------------------------------
    // TODO: The methods and fields below are only needed for products like Hudson,
    // that provide their own LifecycleExecutor and component configuration that extend
    // default implementation, and which may become out-of-date as component requirements
    // are updated within Maven itself.
    public void initialize()
        throws InitializationException
    {
        if ( mavenProjectBuilder == null )
        {
            warnOfIncompleteComponentConfiguration( MavenProjectBuilder.ROLE );
            try
            {
                mavenProjectBuilder = (MavenProjectBuilder) container.lookup( MavenProjectBuilder.ROLE );
            }
            catch ( ComponentLookupException e )
            {
                throw new InitializationException( "Failed to lookup project builder after it was NOT injected via component requirement." );
            }
        }
        
        if ( modelInterpolator == null )
        {
            warnOfIncompleteComponentConfiguration( ModelInterpolator.ROLE );
            try
            {
                modelInterpolator = (ModelInterpolator) container.lookup( ModelInterpolator.ROLE );
            }
            catch ( ComponentLookupException e )
            {
                throw new InitializationException( "Failed to lookup model interpolator after it was NOT injected via component requirement." );
            }
        }
        
        if ( configInterpolator == null )
        {
            warnOfIncompleteComponentConfiguration( ConfigurationInterpolator.ROLE );
            try
            {
                configInterpolator = (ConfigurationInterpolator) container.lookup( ConfigurationInterpolator.ROLE );
            }
            catch ( ComponentLookupException e )
            {
                throw new InitializationException( "Failed to lookup plugin-configuration interpolator after it was NOT injected via component requirement." );
            }
        }
    }
    
    private void warnOfIncompleteComponentConfiguration( final String role )
    {
        StringBuffer buffer = new StringBuffer();
        buffer.append( "\n************ WARNING ************" );
        buffer.append( "\n\nThis Maven runtime contains a LifecycleExecutor component with an incomplete configuration." );
        buffer.append( "\n\nLifecycleExecutor class: " ).append( getClass().getName() );
        buffer.append( "\nMissing component requirement: " ).append( role );
        buffer.append( "\n" );
        buffer.append( "\nNOTE: This seems to be a third-party Maven derivative you are using. If so, please" );
        buffer.append( "\nnotify the developers for this derivative project of the problem. The Apache Maven team is not" );
        buffer.append( "\nresponsible for maintaining the integrity of third-party component overrides." );
        buffer.append( "\n\n" );
        
        getLogger().warn( buffer.toString() );
    }

    private PlexusContainer container;

    public void contextualize( final Context context )
        throws ContextException
    {
        container = (PlexusContainer) context.get( PlexusConstants.PLEXUS_KEY );
    }
}
