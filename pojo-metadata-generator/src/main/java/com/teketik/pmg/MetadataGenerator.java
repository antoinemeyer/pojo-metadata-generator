package com.teketik.pmg;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.reflections.Reflections;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import com.google.common.collect.Lists;
import com.google.common.collect.Multisets;
import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JEnumConstant;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;

@Mojo(name = "pmg", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class MetadataGenerator extends AbstractMojo {
	
	public static final String DEFAULT_TARGET = "./target/pmg";

	@Component
	private MavenProject project;

	@Parameter
	private String[] packages;
	
	@Parameter
	private String target;
	
	private File targetDirectory;

	@Parameter
	private int depthLimit = 1;

	public void execute() throws MojoExecutionException {
		getLog().info("--Processing Pojo Metadata Generator--");

		//assert we have correct parameters
		if(packages == null || packages.length == 0) {
			throw new MojoExecutionException("You must specify the `packages` to scan in the configuration of the plugin.");
		}
		
		//initialize target path
		if (target == null) {
			target = DEFAULT_TARGET;
		}
		//clear it
		try {
			FileUtils.deleteDirectory(target);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Cannot clear the target directory : `"+target+"`", e);
		}
		targetDirectory = new File(target);
		targetDirectory.mkdirs();
		
		// prepare classloader
		Set<URL> urls = new HashSet<URL>();
		try {
			for (Object element : project.getCompileClasspathElements()) {
				urls.add(new File(element.toString()).toURI().toURL());
			}
		}
		catch (MalformedURLException|DependencyResolutionRequiredException e1) {
			throw new MojoExecutionException("Cannot prepare classloader", e1);
		}
		ClassLoader contextClassLoader = URLClassLoader.newInstance(urls.toArray(new URL[0]), Thread.currentThread()
				.getContextClassLoader());
		Thread.currentThread().setContextClassLoader(contextClassLoader);

		// scan packages
		for (String packageName : packages) {
			getLog().info("Scanning package: " + packageName);

			List<ClassLoader> classLoadersList = new LinkedList<ClassLoader>();
			classLoadersList.add(ClasspathHelper.contextClassLoader());
			classLoadersList.add(ClasspathHelper.staticClassLoader());

			Reflections reflections = new Reflections(new ConfigurationBuilder()
					.setScanners(new SubTypesScanner(false /* don't exclude Object.class */), new ResourcesScanner())
					.setUrls(ClasspathHelper.forClassLoader(classLoadersList.toArray(new ClassLoader[0])))
					.filterInputsBy(new FilterBuilder().include(FilterBuilder.prefix(packageName))));

			Set<Class<? extends Object>> subTypes = reflections.getSubTypesOf(Object.class);
			for (Class<? extends Object> subType : subTypes) {
				try {
					writeClass(subType, subTypes);
				}
				catch (JClassAlreadyExistsException | IOException e) {
					getLog().warn("Cannot generate metadata of "+subType.getName(), e);
				}
			}

		}

		getLog().info("--------------------------------------");
	}

	private void writeClass(Class<? extends Object> clazz, Set<Class<? extends Object>> subTypes) throws JClassAlreadyExistsException, IOException {
		getLog().info("processing class: "+clazz.getName());
		
		JCodeModel cm = new JCodeModel();
		JDefinedClass enumClass = cm._class(clazz.getName()+"MetaData", ClassType.ENUM);
		
		//define field type
        JFieldVar typeField = enumClass.field(JMod.PRIVATE|JMod.FINAL, Class.class, "type");
		
        //create constructor
        JMethod enumConstructor = enumClass.constructor(JMod.PRIVATE);
        enumConstructor.param(Class.class, "type");
        enumConstructor.body().assign(JExpr._this().ref ("type"), JExpr.ref("type"));
        
        //getters
        JMethod getterFilterMethod = enumClass.method(JMod.PUBLIC, Class.class, "type");
        getterFilterMethod.body()._return(typeField);
 
        //then process all the fields
        boolean processed = processFields(clazz, subTypes, enumClass, "");

        //also override the toString() method to return the value with dots instead of _
        JMethod toStringMethod = enumClass.method(JMod.PUBLIC, String.class, "toString");
        toStringMethod.body()._return(JExpr.direct("name().replace(\"_\",\".\")"));
        
        /*
         * Only generate the enum file if we have processed at least one field 
         * (as it would create an incorrect enum otherwise) 
         */
        
        //if we have processed at least one field
        if (processed) {

        	//write the file
        	cm.build(targetDirectory);
        }
		
	}

	private boolean processFields(Class<? extends Object> clazz, Set<Class<? extends Object>> subTypes, JDefinedClass enumClass, String base) {
		boolean processed = false;
		for (Field field : clazz.getDeclaredFields()) {

        	//if not static
        	if (!Modifier.isStatic(field.getModifiers())) {

        		getLog().info("generating field: "+base + field.getName());
        		processed = true;
        		
        		//generate 
        		JEnumConstant enumConst = enumClass.enumConstant(base + field.getName());
        		enumConst.arg(JExpr.direct(field.getType().getName()+".class"));
        		

        		//if this field is a subtype
        		//or a collection of subtype
        		Class<?> type;
        		if (Collection.class.isAssignableFrom(field.getType())) {
					type = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        		} else {
        			type = field.getType();
        		}
				if (subTypes.contains(type)) {
        			
        			//if we have not reached the depth limit
        			if (Collections.frequency(Lists.newArrayList(base.split("_")), field.getName()) < depthLimit) {
        				
        				//compute all possibilities
        				processFields(type, subTypes, enumClass, base + field.getName() + "_");
        				
        			}
        		}
        		
        	}
		}
		return processed;
	}
	
	
	
}
