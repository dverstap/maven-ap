package org.apache.maven.artifact.test;

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
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.codehaus.plexus.PlexusTestCase;
import org.codehaus.plexus.util.ReaderFactory;

import java.io.File;

/**
 * Test case that builds standard artifact stuff like repositories.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @version $Id: ArtifactTestCase.java 657432 2008-05-17 19:29:41Z hboutemy $
 */
public abstract class ArtifactTestCase
    extends PlexusTestCase
{
    private ArtifactRepository localRepository;

    protected File getLocalArtifactPath( Artifact artifact )
    {
        return new File( localRepository.getBasedir(), localRepository.pathOf( artifact ) );
    }

    protected void setUp()
        throws Exception
    {
        super.setUp();

        String localRepo = System.getProperty( "maven.repo.local" );

        if ( localRepo == null )
        {
            File settingsFile = new File( System.getProperty( "user.home" ), ".m2/settings.xml" );
            if ( settingsFile.exists() )
            {
                Settings settings = new SettingsXpp3Reader().read( ReaderFactory.newXmlReader( settingsFile ) );
                localRepo = settings.getLocalRepository();
            }
        }
        if ( localRepo == null )
        {
            localRepo = System.getProperty( "user.home" ) + "/.m2/repository";
        }

        ArtifactRepositoryLayout repositoryLayout = (ArtifactRepositoryLayout) container.lookup(
            ArtifactRepositoryLayout.ROLE, "default" );

        localRepository = new DefaultArtifactRepository( "local", "file://" + localRepo, repositoryLayout );
    }

}
