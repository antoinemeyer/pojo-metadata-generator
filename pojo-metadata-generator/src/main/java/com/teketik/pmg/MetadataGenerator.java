package com.teketik.pmg;

import japa.parser.JavaParser;
import japa.parser.ParseException;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.body.FieldDeclaration;
import japa.parser.ast.type.ClassOrInterfaceType;
import japa.parser.ast.type.PrimitiveType;
import japa.parser.ast.type.ReferenceType;
import japa.parser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sun.codemodel.ClassType;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JEnumConstant;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;

/**
 * @goal pmg
 * @requiresDependencyResolution compile
 */
@Mojo(name = "pmg", requiresDependencyResolution = ResolutionScope.COMPILE)
public class MetadataGenerator extends AbstractMojo {
	
	/**
	 * @component
	 */
	@Component
	private MavenProject project;

    /**
     * @parameter
     */
	@Parameter
	private String[] packages;
	  
	/** 
     * @parameter expression="${project.build.sourceDirectory}"
     */
	@Parameter
    private File sourceDirectory;
	
	/**
	 * @parameter default-value="./target/pmg"
	 */
	@Parameter
	private String target = "./target/pmg";
	
	private File targetDirectory;

	/**
	 * @parameter default-value=1
	 */
	@Parameter
	private int depthLimit = 1;

	private class VisitResult {
		List<FieldDeclaration> fields = Lists.newArrayList();
		List<ImportDeclaration> imports = Lists.newArrayList();
	}
	
	//simple class name to visitresults
	private Map<String, VisitResult> map = Maps.newHashMap(); 
	//full class name to visitresults
	private Map<String, VisitResult> fullNamesMap = Maps.newHashMap();
	//simple name to fullname
	private Map<String,String> simpleNameToFullName = Maps.newHashMap();
	
	@SuppressWarnings("unchecked")
	public void execute() throws MojoExecutionException {
		getLog().info("--Processing Pojo Metadata Generator--");

		//assert we have correct parameters
		if(packages == null || packages.length == 0) {
			throw new MojoExecutionException("You must specify the `packages` to scan in the configuration of the plugin.");
		}
		
		//initialize target path
		try {
			FileUtils.deleteDirectory(target);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Cannot clear the target directory : `"+target+"`", e);
		}
		targetDirectory = new File(target);
		targetDirectory.mkdirs();
		getLog().info("writing in: "+targetDirectory.getAbsolutePath());
		
		// scan packages
		for (String packageName : packages) {

			//get src directory
			File srcPackage = new File(sourceDirectory + "/" + packageName.replace(".", "/"));
			
			getLog().info("Scanning package: " + packageName);

			try {

				for (final File f : srcPackage.listFiles()) {
					final String javaName = f.getName().split("\\.")[0];
					getLog().info("extracting fields from class: "+javaName);

			        new VoidVisitorAdapter() {

			        	@Override
			        	public void visit(ImportDeclaration n, Object arg) {
			        		if (map.get(javaName) == null) {
			        			map.put(javaName, new VisitResult());
			        		}
			        		map.get(javaName).imports.add(n);
			        	};
			        	
			        	@Override
			        	public void visit(FieldDeclaration arg0, Object arg1) {
			        		if (map.get(javaName) == null) {
			        			map.put(javaName, new VisitResult());
			        		}
			        		map.get(javaName).fields.add(arg0);
			        	}
			        	
			        }.visit(JavaParser.parse(f), null);
				}
				
			} catch (IOException | ParseException e) {
				throw new MojoExecutionException("exception while scanning package "+packageName, e);				
			}
		
			//fill the missing packages in the fields
			for (Entry<String, VisitResult> entry : map.entrySet()) {

				//add the package name to the class names
				for (FieldDeclaration field : entry.getValue().fields) {
					
					final String typeName = getTypeName(field);
	        		
					//check if this field is in the import
	        		Optional<ImportDeclaration> tryFind = Iterables.tryFind(entry.getValue().imports, new Predicate<ImportDeclaration>() {
	        			@Override
	        			public boolean apply(ImportDeclaration input) {
	        				return input.getName().getName().equals(typeName);
	        			}
	        		});
	        		if (tryFind.isPresent()) {
	        			field.setType(new ClassOrInterfaceType(tryFind.get().getName().toString()));
	        		} 
	        		//if it is not in the imports
	        		else {
	        			//check if it is in another file in this package
	        			Optional<String> tryFind2 = Iterables.tryFind(map.keySet(), new Predicate<String>() {
	        				@Override
	        				public boolean apply(String input) {
	        					String string = input.split("\\.")[0];
	        					System.out.println(string + " - "+typeName);
								return string.equals(typeName);
	        				}
	        			});
	        			if (tryFind2.isPresent()) {
	        				String fullName = packageName+"."+typeName;
							field.setType(new ClassOrInterfaceType(fullName));
	        				simpleNameToFullName.put(typeName, fullName);	        				
	        			} 
	        			//if it's not, it is a java.lang
	        			else {
	        				field.setType(new ClassOrInterfaceType("java.lang."+typeName));
	        			}
	        		}
					
				}
				
			}

			//fill the missing packages in the full names map map
			for (Entry<String, String> entry : simpleNameToFullName.entrySet()) {
				fullNamesMap.put(entry.getValue(), map.get(entry.getKey()));
			}
			
			//now write all the model files
			for (Entry<String, VisitResult> entry : fullNamesMap.entrySet()) {
				process(entry.getKey(), entry.getValue().fields);
			}
			
		}
		
		getLog().info("--------------------------------------");
	}


	private String getTypeName(FieldDeclaration field) {
		final String typeName;
		if (field.getType() instanceof PrimitiveType) {
			typeName = ((PrimitiveType) field.getType()).getType().name();
		} else {
			typeName = ((ClassOrInterfaceType)((ReferenceType)field.getType()).getType()).getName();
		}
		return typeName;
	}

	

	public void process(String clazz, List<FieldDeclaration> fields) throws MojoExecutionException {
		
		try {

			JCodeModel cm = new JCodeModel();
			JDefinedClass enumClass;
			enumClass = cm._class(clazz+"MetaData", ClassType.ENUM)._implements(MetaData.class);
			
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
	        boolean processed = processFields(clazz, fields, enumClass, "");

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
		catch (JClassAlreadyExistsException | IOException | ClassNotFoundException e) {
			throw new MojoExecutionException("exception while processing "+clazz, e);				
		}
		
	}

	private boolean processFields(String clazz, List<FieldDeclaration> fields, JDefinedClass enumClass, String base) throws ClassNotFoundException {
		boolean processed = false;
		for (FieldDeclaration field : fields) {

        	//if not static
        	if (!Modifier.isStatic(field.getModifiers())) {

        		String fieldName = field.getVariables().get(0).getId().getName();
        		String typeName = ((ClassOrInterfaceType)field.getType()).getName();

				getLog().info("generating field: "+base + fieldName);
        		processed = true;
        		
        		//generate 
        		JEnumConstant enumConst = enumClass.enumConstant(base + fieldName);
				enumConst.arg(JExpr.direct(typeName+".class"));
				
        		//if this field is a subtype
        		//or a collection of subtype
//        		if (Collection.class.isAssignableFrom(type)) {//TODO check
//					type = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
//        		} else
				if (fullNamesMap.containsKey(typeName)) {
        			
        			//if we have not reached the depth limit
        			if (Collections.frequency(Lists.newArrayList(base.split("_")), fieldName) < depthLimit) {
        				
        				//compute all possibilities
        				processFields(typeName, fullNamesMap.get(typeName).fields, enumClass, base + fieldName + "_");
        				
        			}
        		}
        		
        	}
		}
		return processed;
	}
	
	
}
