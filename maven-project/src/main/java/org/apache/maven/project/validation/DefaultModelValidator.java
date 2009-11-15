package org.apache.maven.project.validation;

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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.apache.maven.model.Repository;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.util.Iterator;
import java.util.List;

/**
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @version $Id: DefaultModelValidator.java 800540 2009-08-03 20:31:37Z jdcasey $
 */
public class DefaultModelValidator
    implements ModelValidator
{
    private static final String ID_REGEX = "[A-Za-z0-9_\\-.]+";

    ///////////////////////////////////////////////////////////////////////////
    // ModelValidator Implementation

    public ModelValidationResult validate( final Model model )
    {
        ModelValidationResult result = new ModelValidationResult();

        validateStringNotEmpty( "modelVersion", result, model.getModelVersion() );

        validateId( "groupId", result, model.getGroupId() );

        validateId( "artifactId", result, model.getArtifactId() );

        validateStringNotEmpty( "packaging", result, model.getPackaging() );
        
        if ( !model.getModules().isEmpty() && !"pom".equals( model.getPackaging() ) )
        {
            result.addMessage( "Packaging '" + model.getPackaging() + "' is invalid. Aggregator projects " +
                    "require 'pom' as packaging." );
        }
        
        Parent parent = model.getParent();
        if ( parent != null )
        {
            if ( parent.getGroupId().equals( model.getGroupId() ) && 
                    parent.getArtifactId().equals( model.getArtifactId() ) )
            {
                result.addMessage( "The parent element cannot have the same ID as the project." );
            }
        }

        validateStringNotEmpty( "version", result, model.getVersion() );

        for ( Iterator it = model.getDependencies().iterator(); it.hasNext(); )
        {
            Dependency d = (Dependency) it.next();

            validateId( "dependencies.dependency.artifactId", result, d.getArtifactId() );

            validateId( "dependencies.dependency.groupId", result, d.getGroupId() );

            validateStringNotEmpty( "dependencies.dependency.type", result, d.getType(), d.getManagementKey() );

            validateStringNotEmpty( "dependencies.dependency.version", result, d.getVersion(), d.getManagementKey() );

            if ( Artifact.SCOPE_SYSTEM.equals( d.getScope() ) )
            {
                String systemPath = d.getSystemPath();
                
                if ( StringUtils.isEmpty( systemPath ) )
                {
                    result.addMessage( "For dependency " + d + ": system-scoped dependency must specify systemPath." );
                }
                else
                {
                    if ( ! new File( systemPath ).isAbsolute() )
                    {
                        result.addMessage( "For dependency " + d + ": system-scoped dependency must " +
                                "specify an absolute path systemPath." );
                    }
                }
            }
            else if ( StringUtils.isNotEmpty( d.getSystemPath() ) )
            {
                result.addMessage(
                    "For dependency " + d + ": only dependency with system scope can specify systemPath." );
            }
        }

        DependencyManagement mgmt = model.getDependencyManagement();
        if ( mgmt != null )
        {
            for ( Iterator it = mgmt.getDependencies().iterator(); it.hasNext(); )
            {
                Dependency d = (Dependency) it.next();

                validateSubElementStringNotEmpty( d, "dependencyManagement.dependencies.dependency.artifactId", result,
                                                  d.getArtifactId() );

                validateSubElementStringNotEmpty( d, "dependencyManagement.dependencies.dependency.groupId", result,
                                                  d.getGroupId() );

                if ( Artifact.SCOPE_SYSTEM.equals( d.getScope() ) )
                {
                    String systemPath = d.getSystemPath();
                    
                    if ( StringUtils.isEmpty( systemPath ) )
                    {
                        result.addMessage( "For managed dependency " + d + ": system-scoped dependency must specify systemPath." );
                    }
                    else
                    {
                        if ( ! new File( systemPath ).isAbsolute() )
                        {
                            result.addMessage( "For managed dependency " + d + ": system-scoped dependency must " +
                                    "specify an absolute path systemPath." );
                        }
                    }
                }
                else if ( StringUtils.isNotEmpty( d.getSystemPath() ) )
                {
                    result.addMessage(
                        "For managed dependency " + d + ": only dependency with system scope can specify systemPath." );
                }
                else if ( Artifact.SCOPE_IMPORT.equals( d.getScope() ) )
                {
                    if ( !"pom".equals( d.getType() ) )
                    {
                        result.addMessage( "For managed dependency " + d
                            + ": dependencies with import scope must have type 'pom'." );
                    }
                    else if ( d.getClassifier() != null )
                    {
                        result.addMessage( "For managed dependency " + d
                            + ": dependencies with import scope must NOT have a classifier." );
                    }

                }
            }
        }

        Build build = model.getBuild();
        if ( build != null )
        {
            for ( Iterator it = build.getPlugins().iterator(); it.hasNext(); )
            {
                Plugin p = (Plugin) it.next();

                validateStringNotEmpty( "build.plugins.plugin.artifactId", result, p.getArtifactId() );

                validateStringNotEmpty( "build.plugins.plugin.groupId", result, p.getGroupId() );
            }

            for ( Iterator it = build.getResources().iterator(); it.hasNext(); )
            {
                Resource r = (Resource) it.next();

                validateStringNotEmpty( "build.resources.resource.directory", result, r.getDirectory() );
            }

            for ( Iterator it = build.getTestResources().iterator(); it.hasNext(); )
            {
                Resource r = (Resource) it.next();

                validateStringNotEmpty( "build.testResources.testResource.directory", result, r.getDirectory() );
            }
        }

        Reporting reporting = model.getReporting();
        if ( reporting != null )
        {
            for ( Iterator it = reporting.getPlugins().iterator(); it.hasNext(); )
            {
                ReportPlugin p = (ReportPlugin) it.next();

                validateStringNotEmpty( "reporting.plugins.plugin.artifactId", result, p.getArtifactId() );

                validateStringNotEmpty( "reporting.plugins.plugin.groupId", result, p.getGroupId() );
            }
        }

        validateRepositories( result, model.getRepositories(), "repositories.repository" );

        validateRepositories( result, model.getPluginRepositories(), "pluginRepositories.pluginRepository" );

        forcePluginExecutionIdCollision( model, result );

        return result;
    }

    private boolean validateId( final String fieldName, final ModelValidationResult result, final String id )
    {
        if ( !validateStringNotEmpty( fieldName, result, id ) )
        {
            return false;
        }
        else
        {
            boolean match = id.matches( ID_REGEX );
            if ( !match )
            {
                result.addMessage( "'" + fieldName + "' with value '" + id + "' does not match a valid id pattern." );
            }
            return match;
        }
    }

    private void validateRepositories( final ModelValidationResult result, final List repositories, final String prefix )
    {
        for ( Iterator it = repositories.iterator(); it.hasNext(); )
        {
            Repository repository = (Repository) it.next();

            validateStringNotEmpty( prefix + ".id", result, repository.getId() );

            validateStringNotEmpty( prefix + ".url", result, repository.getUrl() );
        }
    }

    private void forcePluginExecutionIdCollision( final Model model, final ModelValidationResult result )
    {
        Build build = model.getBuild();

        if ( build != null )
        {
            List plugins = build.getPlugins();

            if ( plugins != null )
            {
                for ( Iterator it = plugins.iterator(); it.hasNext(); )
                {
                    Plugin plugin = (Plugin) it.next();

                    // this will force an IllegalStateException, even if we don't have to do inheritance assembly.
                    try
                    {
                        plugin.getExecutionsAsMap();
                    }
                    catch ( IllegalStateException collisionException )
                    {
                        result.addMessage( collisionException.getMessage() );
                    }
                }
            }
        }
    }


    // ----------------------------------------------------------------------
    // Field validation
    // ----------------------------------------------------------------------

    private boolean validateStringNotEmpty( final String fieldName, final ModelValidationResult result, final String string )
    {
        return validateStringNotEmpty( fieldName, result, string, null );
    }

    /**
     * Asserts:
     * <p/>
     * <ul>
     * <li><code>string.length != null</code>
     * <li><code>string.length > 0</code>
     * </ul>
     */
    private boolean validateStringNotEmpty( final String fieldName, final ModelValidationResult result, final String string, final String sourceHint )
    {
        if ( !validateNotNull( fieldName, result, string, sourceHint ) )
        {
            return false;
        }

        if ( string.length() > 0 )
        {
            return true;
        }

        if ( sourceHint != null )
        {
            result.addMessage( "'" + fieldName + "' is missing for " + sourceHint );
        }
        else
        {
            result.addMessage( "'" + fieldName + "' is missing." );
        }


        return false;
    }

    /**
     * Asserts:
     * <p/>
     * <ul>
     * <li><code>string.length != null</code>
     * <li><code>string.length > 0</code>
     * </ul>
     */
    private boolean validateSubElementStringNotEmpty( final Object subElementInstance, final String fieldName,
                                                      final ModelValidationResult result, final String string )
    {
        if ( !validateSubElementNotNull( subElementInstance, fieldName, result, string ) )
        {
            return false;
        }

        if ( string.length() > 0 )
        {
            return true;
        }

        result.addMessage( "In " + subElementInstance + ":\n\n       -> '" + fieldName + "' is missing." );

        return false;
    }

    /**
     * Asserts:
     * <p/>
     * <ul>
     * <li><code>string != null</code>
     * </ul>
     */
    private boolean validateNotNull( final String fieldName, final ModelValidationResult result, final Object object, final String sourceHint )
    {
        if ( object != null )
        {
            return true;
        }

        if ( sourceHint != null )
        {
            result.addMessage( "'" + fieldName + "' is missing for " + sourceHint );
        }
        else
        {
            result.addMessage( "'" + fieldName + "' is missing." );
        }

        return false;
    }

    /**
     * Asserts:
     * <p/>
     * <ul>
     * <li><code>string != null</code>
     * </ul>
     */
    private boolean validateSubElementNotNull( final Object subElementInstance, final String fieldName,
                                               final ModelValidationResult result, final Object object )
    {
        if ( object != null )
        {
            return true;
        }

        result.addMessage( "In " + subElementInstance + ":\n\n       -> '" + fieldName + "' is missing." );

        return false;
    }
}
