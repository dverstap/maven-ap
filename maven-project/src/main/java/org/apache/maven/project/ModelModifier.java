/*
 * Copyright 2009 Davy Verstappen.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.project;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.logging.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ModelModifier {

    protected final Logger logger;
    protected final Model model;
    protected final File pomFile;

    public ModelModifier(Logger logger, Model model, File pomFile) {
        this.logger = logger;
        this.model = model;
        this.pomFile = pomFile;
    }

    public File getBasedir() {
        return pomFile.getParentFile();
    }

    public String getGroupId() {
        if (model.getGroupId() != null) {
            return model.getGroupId();
        }

        if (model.getParent() != null && model.getParent().getGroupId() != null) {
            return model.getParent().getGroupId();
        }

        throw new IllegalStateException("groupId not set in " + pomFile + " nor in the parent pom.");
    }

    public File execute() {
        // TODO check groupId prefix
        if (getGroupId().startsWith("com.github.dverstap")) {
            System.out.println("GOING TO MODIFY POM FILE: " + pomFile);

            String versionPropertyName = getVersionPropertyName();
            String version = System.getProperty(versionPropertyName);
            if (version == null || version.trim().length() == 0) {
                throw new IllegalStateException("Parent version system property " + versionPropertyName + " is not set in " + pomFile);
            }
            // doesn't work
//            Object modelProperty = model.getProperties().get(versionPropertyName);
//            if (modelProperty != null) {
//                throw new IllegalStateException("Parent version system property " + versionPropertyName + " is not set in " + pomFile);
//            }
//            model.getProperties().setProperty(versionPropertyName, version);
            model.setVersion(version);
            if (model.getParent() != null) {
                model.getParent().setVersion(version);
            }
            try {
                return saveAutopomFile();
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        } else {
            return null;
        }
    }

    private String getVersionPropertyName() {
        String parentVersion = null;
        if (model.getParent() != null) {
            parentVersion = model.getParent().getVersion().trim();
        }
        String thisVersion = model.getVersion();
        String version = checkConsistency(parentVersion, thisVersion);

        if (version.startsWith("${") && version.endsWith("}")) {
            return version.substring(2, version.length() - 1);
        } else {
            throw new IllegalStateException("Parent/this version field is not a property in " + pomFile);
        }
    }

    private String checkConsistency(String parentVersion, String thisVersion) {
        if (parentVersion != null && thisVersion != null) {
            if (!parentVersion.equals(thisVersion)) {
                throw new IllegalStateException("/project/parent/version and /project/version are diffent");
            }
            return thisVersion;
        } else if (parentVersion != null) {
            return parentVersion;
        } else if (thisVersion != null) {
            return thisVersion;
        } else {
            throw new IllegalStateException("/project/parent/version nor /project/version are defined");
        }
    }

    /*
     * Unfortunately, we must write the auto-pom.xml file in the basedir and not in target,
     * because the MavengetBasedir() method is implemented by taking the parent from the pom file.
     */
    private File saveAutopomFile() throws IOException {
        File newPomFile = new File(getBasedir(), "pom-ap.xml");
        FileWriter out = null;
        try {
            newPomFile.getParentFile().mkdirs();
            out = new FileWriter(newPomFile);
            MavenXpp3Writer writer = new MavenXpp3Writer();
            writer.write(out, model);
        } finally {
            if (out != null) {
                out.close();
            }
        }
        return newPomFile;
    }

}
