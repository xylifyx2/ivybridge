/**
 * Copyright © 2015, QIAGEN Aarhus A/S. All rights reserved.
 */
package dk.profundo.ivybridge.ivy;

/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.IvyContext;
import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DependencyArtifactDescriptor;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ExcludeRule;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.core.settings.IvyVariableContainer;
import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorWriter;
import org.apache.ivy.plugins.parser.m2.PomWriterOptions;
import org.apache.ivy.plugins.parser.m2.PomWriterOptions.ConfigurationScopeMapping;
import org.apache.ivy.plugins.parser.m2.PomWriterOptions.ExtraDependency;
import org.apache.ivy.util.ConfigurationUtils;

public final class PomWriter {
    
    private static final String SKIP_LINE = "SKIP_LINE";
    
    private static final ConfigurationScopeMapping DEFAULT_MAPPING 
            = new ConfigurationScopeMapping(new HashMap() {
                {
                    put("compile", "compile");
                    put("runtime", "runtime");
                    put("provided", "provided");
                    put("test", "test");
                    put("system", "system");
                }
            });


    private PomWriter() {
    }
    
    public static void write(ModuleDescriptor md, File output, PomWriterOptions options)
            throws IOException {
        LineNumberReader in;
        if (options.getTemplate() == null) {
            in = new LineNumberReader(new InputStreamReader(PomModuleDescriptorWriter.class.getResourceAsStream("pom.template")));
        } else {
            in = new LineNumberReader(new InputStreamReader(new FileInputStream(options.getTemplate())));
        }
        
        if (output.getParentFile() != null) {
            output.getParentFile().mkdirs();
        }
        PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(output), "UTF-8"));
        try {
            IvySettings settings = IvyContext.getContext().getSettings();
            IvyVariableContainer variables = new IvyVariableContainerWrapper(settings.getVariableContainer());
            
            variables.setVariable("ivy.pom.license", SKIP_LINE, true);
            variables.setVariable("ivy.pom.header", SKIP_LINE, true);
            variables.setVariable("ivy.pom.groupId", SKIP_LINE, true);
            variables.setVariable("ivy.pom.artifactId", SKIP_LINE, true);
            variables.setVariable("ivy.pom.version", SKIP_LINE, true);
            variables.setVariable("ivy.pom.packaging", SKIP_LINE, true);
            variables.setVariable("ivy.pom.name", SKIP_LINE, true);
            variables.setVariable("ivy.pom.description", SKIP_LINE, true);
            variables.setVariable("ivy.pom.url", SKIP_LINE, true);
            
            if (options.getLicenseHeader() != null) {
                variables.setVariable("ivy.pom.license", options.getLicenseHeader(), true);
            }
            if (options.isPrintIvyInfo()) {
                String header = "<!--\n"
                              + "   Apache Maven 2 POM generated by Apache Ivy\n"
                              + "   " + Ivy.getIvyHomeURL() + "\n"
                              + "   Apache Ivy version: " + Ivy.getIvyVersion() 
                                          + " " + Ivy.getIvyDate() + "\n"
                              + "-->";
                variables.setVariable("ivy.pom.header", header, true);
            }
            
            setModuleVariables(md, variables, options);
            
            boolean dependenciesPrinted = false;
            
            int lastIndent = 0;
            int indent = 0;
            String line = in.readLine();
            while (line != null) {
                line = IvyPatternHelper.substituteVariables(line, variables);
                if (line.indexOf(SKIP_LINE) != -1) {
                    // skip this line
                    line = in.readLine();
                    continue;
                }
                
                if (line.trim().length() == 0) {
                    // empty line
                    out.println(line);
                    line = in.readLine();
                    continue;
                }
                
                lastIndent = indent;
                indent = line.indexOf('<');
                
                if (!dependenciesPrinted && line.indexOf("</dependencies>") != -1) {
                    printDependencies(md, out, options, indent, false);
                    dependenciesPrinted = true;
                }
                
                if (!dependenciesPrinted && line.indexOf("</project>") != -1) {
                    printDependencies(md, out, options, lastIndent, true);
                    dependenciesPrinted = true;
                }

                out.println(line);
                line = in.readLine();
            }
        } finally {
            in.close();
            out.close();
        }
    }

    private static void setModuleVariables(ModuleDescriptor md, IvyVariableContainer variables, PomWriterOptions options) {
        ModuleRevisionId mrid = md.getModuleRevisionId();
        variables.setVariable("ivy.pom.groupId", mrid.getOrganisation(), true);
        
        String artifactId = options.getArtifactName();
        if (artifactId == null) {
            artifactId = mrid.getName();
        }
        
        String packaging = options.getArtifactPackaging();
        if (packaging == null) {
            // find artifact to determine the packaging
            Artifact artifact = findArtifact(md, artifactId);
            if (artifact == null) {
                // no suitable artifact found, default to 'pom'
                packaging = "pom";
            } else {
                packaging = artifact.getType();
            }
        }
        
        variables.setVariable("ivy.pom.artifactId", artifactId, true);
        variables.setVariable("ivy.pom.packaging", packaging, true);
        if (mrid.getRevision() != null) {
            variables.setVariable("ivy.pom.version", mrid.getRevision(), true);
        }
        if (options.getDescription() != null) {
            variables.setVariable("ivy.pom.description", options.getDescription(), true);
        }
        if (md.getHomePage() != null) {
            variables.setVariable("ivy.pom.url", md.getHomePage(), true);
        }
    }
    
    /**
     * Returns the first artifact with the correct name and without a classifier.
     */
    private static Artifact findArtifact(ModuleDescriptor md, String artifactName) {
        Artifact[] artifacts = md.getAllArtifacts();
        for (int i = 0; i < artifacts.length; i++) {
            if (artifacts[i].getName().equals(artifactName)
                    && artifacts[i].getAttribute("classifier") == null) {
                return artifacts[i];
            }
        }
        
        return null;
    }
    
    private static void indent(PrintWriter out, int indent) {
        for (int i = 0; i < indent; i++) {
            out.print(' ');
        }
    }

    private static void printDependencies(ModuleDescriptor md, PrintWriter out, 
            PomWriterOptions options, int indent, boolean printDependencies) {
        List extraDeps = options.getExtraDependencies();
        DependencyDescriptor[] dds = getDependencies(md, options);

        if (!extraDeps.isEmpty() || (dds.length > 0)) {
            if (printDependencies) {
                indent(out, indent);
                out.println("<dependencies>");
            }
            
            // print the extra dependencies first
            for (Iterator it = extraDeps.iterator(); it.hasNext(); ) {
                PomWriterOptions.ExtraDependency dep = (ExtraDependency) it.next();
                String groupId = dep.getGroup();
                if (groupId == null) {
                    groupId = md.getModuleRevisionId().getOrganisation();
                }
                String version = dep.getVersion();
                if (version == null) {
                    version = md.getModuleRevisionId().getRevision();
                }
                printDependency(out, indent, groupId, dep.getArtifact(), version, dep.getType(), 
                        dep.getClassifier(), dep.getScope(), dep.isOptional(), null);
            }
            
            // now print the dependencies listed in the ModuleDescriptor
            ConfigurationScopeMapping mapping = options.getMapping();
            if (mapping == null) {
                mapping = DEFAULT_MAPPING;
            }
            
            for (int i = 0; i < dds.length; i++) {
                ModuleRevisionId mrid = dds[i].getDependencyRevisionId();
                ExcludeRule[] excludes = null;
                if(dds[i].canExclude()){
                    excludes = dds[i].getAllExcludeRules();
                }
                DependencyArtifactDescriptor[] dads = dds[i].getAllDependencyArtifacts();
                if(dads.length > 0) {
                    for (int j = 0; j < dads.length; j++) {
                        String type = dads[j].getType();
                        String classifier = dads[j].getExtraAttribute("classifier");
                        String scope = mapping.getScope(dds[i].getModuleConfigurations());
                        boolean optional = mapping.isOptional(dds[i].getModuleConfigurations());
                        printDependency(out, indent, mrid.getOrganisation(), mrid.getName(), 
                                mrid.getRevision(), type, classifier, scope, optional, excludes);
                    }
                }
                else {
                    String scope = mapping.getScope(dds[i].getModuleConfigurations());
                    boolean optional = mapping.isOptional(dds[i].getModuleConfigurations());
                    printDependency(out, indent, mrid.getOrganisation(), mrid.getName(), 
                            mrid.getRevision(), null, null, scope, optional, excludes);
                }
            }
            
            if (printDependencies) {
                indent(out, indent);
                out.println("</dependencies>");
            }
        }
    }
    
    private static void printDependency(PrintWriter out, int indent, String groupId, 
            String artifactId, String version, String type, String classifier, String scope, 
            boolean isOptional, ExcludeRule[] excludes) {
        indent(out, indent * 2);
        out.println("<dependency>");
        indent(out, indent * 3);
        out.println("<groupId>" + groupId + "</groupId>");
        indent(out, indent * 3);
        out.println("<artifactId>" + artifactId + "</artifactId>");
        indent(out, indent * 3);
        out.println("<version>" + version + "</version>");
        if (type != null && !"jar".equals(type)) {
            indent(out, indent * 3);
            out.println("<type>" + type + "</type>");
        }
        if (classifier != null) {
            indent(out, indent * 3);
            out.println("<classifier>" + classifier + "</classifier>");
        }
        if (scope != null) {
            indent(out, indent * 3);
            out.println("<scope>" + scope + "</scope>");
        }
        if (isOptional) {
            indent(out, indent * 3);
            out.println("<optional>true</optional>");
        }
        if (excludes != null) {
           printExclusions(excludes, out, indent);
        }
        indent(out, indent * 2);
        out.println("</dependency>");
    }
    
    private static void printExclusions(ExcludeRule[] exclusions, PrintWriter out, int indent) {
        indent(out, indent * 3);
        out.println("<exclusions>");        
        
        for(int i = 0; i < exclusions.length; i++ ){
            indent(out, indent * 4);
            out.println("<exclusion>");
            ExcludeRule rule = exclusions[i];
            indent(out, indent * 5);
            out.println("<groupId>" + rule.getId().getModuleId().getOrganisation() + "</groupId>");
            indent(out, indent * 5);
            out.println("<artifactId>" + rule.getId().getModuleId().getName() + "</artifactId>");
            indent(out, indent * 4);
            out.println("</exclusion>"); 
        }
        
        indent(out, indent * 3);
        out.println("</exclusions>");      
    }
    
    private static DependencyDescriptor[] getDependencies(ModuleDescriptor md, 
            PomWriterOptions options) {
        String[] confs = ConfigurationUtils.replaceWildcards(options.getConfs(), md);

        List result = new ArrayList();
        DependencyDescriptor[] dds = md.getDependencies();
        for (int i = 0; i < dds.length; i++) {
            String[] depConfs = dds[i].getDependencyConfigurations(confs);
            if ((depConfs != null) && (depConfs.length > 0)) {
                result.add(dds[i]);
            }
        }
        
        return (DependencyDescriptor[]) result.toArray(new DependencyDescriptor[result.size()]);
    }
    
    /**
     * Wraps an {@link IvyVariableContainer} delegating most method calls to the wrapped instance,
     * except for a set of variables which are only stored locally in the wrapper, and not
     * propagated to the wrapped instance.
     */
    private static final class IvyVariableContainerWrapper implements IvyVariableContainer {
        
        private final IvyVariableContainer variables;

        private Map localVariables = new HashMap();

        private IvyVariableContainerWrapper(IvyVariableContainer variables) {
            this.variables = variables;
        }

        public void setVariable(String varName, String value, boolean overwrite) {
            localVariables.put(varName, value);
        }

        public void setEnvironmentPrefix(String prefix) {
            variables.setEnvironmentPrefix(prefix);
        }

        public String getVariable(String name) {
            String result = variables.getVariable(name);
            if (result == null) {
                result = (String) localVariables.get(name);
            }
            return result;
        }

        public Object clone() {
            throw new UnsupportedOperationException();
        }
    }

}
