package org.apache.maven.project.interpolation;

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

import org.apache.maven.project.path.PathTranslator;

/**
 * @author jdcasey
 * @version $Id: RegexBasedModelInterpolatorTest.java 689163 2008-08-26 18:21:59Z jdcasey $
 */
public class RegexBasedModelInterpolatorTest
    extends AbstractModelInterpolatorTest
{
    protected ModelInterpolator createInterpolator( PathTranslator translator )
        throws Exception
    {
        RegexBasedModelInterpolator interpolator = new RegexBasedModelInterpolator( translator );
        interpolator.initialize();

        return interpolator;
    }

    protected ModelInterpolator createInterpolator()
        throws Exception
    {
        RegexBasedModelInterpolator interpolator = new RegexBasedModelInterpolator();
        interpolator.initialize();

        return interpolator;
    }
}
