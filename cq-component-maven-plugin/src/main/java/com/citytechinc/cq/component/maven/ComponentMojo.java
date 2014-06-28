/**
 *    Copyright 2013 CITYTECH, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.citytechinc.cq.component.maven;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.citytechinc.cq.component.graniteuidialog.widget.GraniteUIWidgetRegistry;
import javassist.ClassPool;
import javassist.CtClass;

import javax.naming.ConfigurationException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.reflections.Reflections;

import com.citytechinc.cq.component.annotations.Component;
import com.citytechinc.cq.component.dialog.ComponentNameTransformer;
import com.citytechinc.cq.component.dialog.widget.WidgetRegistry;
import com.citytechinc.cq.component.dialog.widget.impl.DefaultWidgetRegistry;
import com.citytechinc.cq.component.maven.util.ComponentMojoUtil;
import com.citytechinc.cq.component.maven.util.LogSingleton;

@Mojo(name = "component", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class ComponentMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project}")
	private MavenProject project;

	@Parameter
	private String componentPathBase;

	@Parameter(defaultValue = "content")
	private String componentPathSuffix;

	@Parameter(defaultValue = "Components")
	private String defaultComponentGroup;

	@Parameter(defaultValue = "camel-case")
	private String transformerName;

	@Parameter(required = false)
	private List<Dependency> excludeDependencies;

    @Parameter(defaultValue = "false")
    private boolean generateGraniteDialogs;

	public void execute() throws MojoExecutionException, MojoFailureException {

		LogSingleton.getInstance().setLogger(getLog());

		try {

		    @SuppressWarnings("unchecked")
            List<String> classpathElements = project.getCompileClasspathElements();

			ClassLoader classLoader = ComponentMojoUtil.getClassLoader(classpathElements, this.getClass()
				.getClassLoader());

			ClassPool classPool = ComponentMojoUtil.getClassPool(classLoader);

			Reflections reflections = ComponentMojoUtil.getReflections(classLoader);

			List<CtClass> classList = ComponentMojoUtil.getAllComponentAnnotations(classPool, reflections, getExcludedClasses());

			WidgetRegistry widgetRegistry = new DefaultWidgetRegistry(classPool, classLoader, reflections);

            GraniteUIWidgetRegistry graniteWidgetRegistry = new DefaultGraniteUIWidgetRegistry(classPool, classLoader, reflections);

			Map<String, ComponentNameTransformer> transformers = ComponentMojoUtil.getAllTransformers(classPool,
				reflections);

			ComponentNameTransformer transformer = transformers.get(transformerName);

			if (transformer == null) {
				throw new ConfigurationException("The configured transformer wasn't found");
			}

			ComponentMojoUtil.buildArchiveFileForProjectAndClassList(classList, widgetRegistry, graniteWidgetRegistry, classLoader, classPool,
				new File(project.getBuild().getDirectory()), componentPathBase, componentPathSuffix,
				defaultComponentGroup, getArchiveFileForProject(), getTempArchiveFileForProject(), transformer);

		} catch (Exception e) {
			getLog().error(e.getMessage(), e);
			throw new MojoExecutionException(e.getMessage(), e);
		}

	}

	private Set<String> getExcludedClasses() throws DependencyResolutionRequiredException, MalformedURLException {

	    getLog().debug("Constructing set of excluded Class names");

	    List<String> excludedDependencyPaths = getExcludedDependencyPaths();

	    if (excludedDependencyPaths != null) {
    	    ClassLoader exclusionClassLoader = ComponentMojoUtil.getClassLoader(excludedDependencyPaths, this
                .getClass().getClassLoader());

    	    Reflections reflections = ComponentMojoUtil.getReflections(exclusionClassLoader);

    	    Set<String> excludedClassNames = reflections.getStore().getTypesAnnotatedWith(Component.class.getName());

    	    return excludedClassNames;
	    }

	    return null;
	}

	@SuppressWarnings("unchecked")
    private List<String> getExcludedDependencyPaths() throws DependencyResolutionRequiredException {
	    if (excludeDependencies != null && !excludeDependencies.isEmpty()) {
	        getLog().debug("Exclusions Found");

	        List<Artifact> compileArtifacts = project.getCompileArtifacts();

	        List<String> excludedClasspathElements = new ArrayList<String>();

	        Set<String> excludedArtifactIdentifiers = new HashSet<String>();

            for (Dependency curDependency : excludeDependencies) {
                excludedArtifactIdentifiers.add(curDependency.getGroupId() + ":" + curDependency.getArtifactId());
            }

            for (Artifact curArtifact : compileArtifacts) {
                String referenceIdentifier = curArtifact.getGroupId() + ":" + curArtifact.getArtifactId();

                if (excludedArtifactIdentifiers.contains(referenceIdentifier)) {
                    MavenProject identifiedProject = (MavenProject) project.getProjectReferences().get(referenceIdentifier);
                    if (identifiedProject != null)
                    {
                        excludedClasspathElements.add(identifiedProject.getBuild().getOutputDirectory());
                        getLog().debug("Excluding " + identifiedProject.getBuild().getOutputDirectory());
                    }
                    else
                    {
                        File file = curArtifact.getFile();
                        if (file == null)
                        {
                            throw new DependencyResolutionRequiredException(curArtifact);
                        }
                        excludedClasspathElements.add(file.getPath());
                        getLog().debug("Excluding " + file.getPath());
                    }
                }
            }

            return excludedClasspathElements;
	    }

	    return null;

	}

	private File getArchiveFileForProject() {
		File buildDirectory = new File(project.getBuild().getDirectory());

		String zipFileName = project.getArtifactId() + "-" + project.getVersion() + ".zip";

		getLog().debug("Determined ZIP file name to be " + zipFileName);

		return new File(buildDirectory, zipFileName);
	}

	private File getTempArchiveFileForProject() {
		File buildDirectory = new File(project.getBuild().getDirectory());

		String zipFileName = project.getArtifactId() + "-" + project.getVersion() + "-temp.zip";

		getLog().debug("Temp archive file name " + zipFileName);

		return new File(buildDirectory, zipFileName);
	}

}
