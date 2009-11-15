package org.apache.maven.usability;

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

import java.io.IOException;

import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.usability.diagnostics.ErrorDiagnoser;
import org.codehaus.plexus.PlexusTestCase;

/**
 * @author Benjamin Bentmann
 * @version $Id: ArtifactResolverDiagnoserTest.java 793305 2009-07-12 09:38:11Z bentmann $
 */
public class ArtifactResolverDiagnoserTest
    extends PlexusTestCase
{

    /**
     * Tests that inner IOException without detail message does not crash diagnoser.
     * 
     * @throws Exception
     */
    public void testNullMessage()
        throws Exception
    {
        ErrorDiagnoser diagnoser =
            (ArtifactResolverDiagnoser) lookup( ErrorDiagnoser.ROLE, "ArtifactResolverDiagnoser" );

        Throwable error = new ArtifactResolutionException( null, null, null, null, null, null, new IOException() );

        assertTrue( diagnoser.canDiagnose( error ) );
        diagnoser.diagnose( error );
    }

}
