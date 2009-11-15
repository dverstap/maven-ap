package org.apache.maven.model;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import junit.framework.TestCase;

/**
 * Tests {@code PluginExecution}.
 * 
 * @author Benjamin Bentmann
 * @version $Id: PluginExecutionTest.java 793311 2009-07-12 10:29:57Z bentmann $
 */
public class PluginExecutionTest
    extends TestCase
{

    public void testHashCodeNullSafe()
    {
        new PluginExecution().hashCode();
    }

    public void testEqualsNullSafe()
    {
        assertFalse( new PluginExecution().equals( null ) );

        new PluginExecution().equals( new PluginExecution() );
    }

    public void testEqualsIdentity()
    {
        PluginExecution thing = new PluginExecution();
        assertTrue( thing.equals( thing ) );
    }

    public void testToStringNullSafe()
    {
        assertNotNull( new PluginExecution().toString() );
    }

}
