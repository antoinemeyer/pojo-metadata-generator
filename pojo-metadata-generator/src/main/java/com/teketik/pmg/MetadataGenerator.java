package com.teketik.pmg;

import japa.parser.JavaParser;
import japa.parser.ParseException;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.body.FieldDeclaration;
import japa.parser.ast.type.ClassOrInterfaceType;
import japa.parser.ast.type.PrimitiveType;
import japa.parser.ast.type.ReferenceType;
import japa.parser.ast.type.Type;
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
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.FileUtils;

import com.google.common.base.Function;
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
import com.sun.codemodel.JType;

/**
 * @goal pmg
 * @requiresDependencyResolution compile
 */
@Mojo(name = "pmg", requiresDependencyResolution = ResolutionScope.COMPILE)
public class MetadataGenerator extends AbstractMojo {

    /**
     * @parameter
     */
	@Parameter
	private String[] packages;
	  
	/** 
     * @parameter property="project.build.sourceDirectory"
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
					Type appendMissingPackage = appendMissingPackage(packageName, entry.getValue().imports, field.getType());
					field.setType(appendMissingPackage);
					
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


	private Type appendMissingPackage(final String packageName, final List<ImportDeclaration> imports, Type fieldType) {
		final String typeName = getTypeName(fieldType);
		//check if this field is in the import
		Optional<ImportDeclaration> tryFind = Iterables.tryFind(imports, new Predicate<ImportDeclaration>() {
			@Override
			public boolean apply(ImportDeclaration input) {
				return input.getName().getName().equals(typeName);
			}
		});
		//if it is in the imports
		if (tryFind.isPresent()) {
			//process it (and keep its type args that we also need to process the same way)
			ClassOrInterfaceType type = new ClassOrInterfaceType(tryFind.get().getName().toString());
			if (fieldType instanceof ReferenceType) {
				ReferenceType fieldCast = ((ReferenceType)fieldType);
				if (fieldCast.getType() instanceof ClassOrInterfaceType) {
					ClassOrInterfaceType fieldTypeCast = ((ClassOrInterfaceType)fieldCast.getType());
					List<Type> typeArgs = fieldTypeCast.getTypeArgs();
					if (typeArgs != null) {
						type.setTypeArgs(Lists.transform(typeArgs, new Function<Type, Type>() {
							@Override
							public Type apply(Type input) {
								return appendMissingPackage(packageName, imports, input);
							}
						}));
					}
				}
			}
			return type;
		} 
		//if it is not in the imports
		else {
			//check if it is in another file in this package
			Optional<String> tryFind2 = Iterables.tryFind(map.keySet(), new Predicate<String>() {
				@Override
				public boolean apply(String input) {
					return input.split("\\.")[0].equals(typeName);
				}
			});
			if (tryFind2.isPresent()) {
				String fullName = packageName+"."+typeName;
				simpleNameToFullName.put(typeName, fullName);	        				
				return new ClassOrInterfaceType(fullName);
			} 
			//if it's not, it is a java.lang
			else {
				return new ClassOrInterfaceType("java.lang."+typeName);
			}
		}
	}

	private String getTypeName(Type type) {
		final String typeName;
		if (type instanceof PrimitiveType) {
			typeName = ((PrimitiveType) type).getType().name();
		} else if (type instanceof ClassOrInterfaceType){
			typeName = ((ClassOrInterfaceType)type).getName();
		} else {
			typeName = ((ClassOrInterfaceType)((ReferenceType)type).getType()).getName();
		}
		return typeName;
	}

	
	/*
	 * http://namanmehta.blogspot.fr/2010/01/use-codemodel-to-generate-java-source.html
	 * 
	 * 
	 */
	
	public void process(String clazz, List<FieldDeclaration> fields) throws MojoExecutionException {
		
		try {

			JCodeModel cm = new JCodeModel();
			JDefinedClass enumClass;
			enumClass = cm._class(clazz+"MetaData", ClassType.ENUM)._implements(MetaData.class);
			
			//define field type
	        JFieldVar typeField = enumClass.field(JMod.PRIVATE|JMod.FINAL, cm.ref(Class.class), "type");
	        JFieldVar typeArgsField = enumClass.field(JMod.PRIVATE|JMod.FINAL, cm.ref(Class.class).array(), "typeArgs");
			
	        //create constructor
	        JMethod enumConstructor = enumClass.constructor(JMod.PRIVATE);
	        enumConstructor.param(cm.ref(Class.class), "type");
	        enumConstructor.varParam(cm.ref(Class.class), "typeArgs");
	        enumConstructor.body().assign(JExpr._this().ref ("type"), JExpr.ref("type"));
	        enumConstructor.body().assign(JExpr._this().ref ("typeArgs"), JExpr.ref("typeArgs"));
	        
	        //getters
	        JMethod getterTypeMethod = enumClass.method(JMod.PUBLIC, cm.ref(Class.class), "type");
	        getterTypeMethod.body()._return(typeField);
	 
	        JMethod getterTypeArgs = enumClass.method(JMod.PUBLIC, cm.ref(Class.class).array(), "typeArgs");
	        getterTypeArgs.body()._return(typeArgsField);
	        
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
        		ClassOrInterfaceType type = (ClassOrInterfaceType)field.getType();

        		String fieldName = field.getVariables().get(0).getId().getName();
				String typeName = type.getName();

				getLog().info("generating field: "+base + fieldName);
        		processed = true;
        		
        		//generate 
        		JEnumConstant enumConst = enumClass.enumConstant(base + fieldName);
				enumConst.arg(JExpr.direct(typeName+".class"));
				
				//if this field is a collection
				//process type args
				if (type.getTypeArgs() != null) {
					for (Type typeArg : type.getTypeArgs()) {
						String typeArgName = getTypeName(typeArg);
						processModelTypeName(enumClass, base, fieldName, typeArgName);
						enumConst.arg(JExpr.direct(typeArgName+".class"));
					}
				}

				//process eventual subtypes
				processModelTypeName(enumClass, base, fieldName, typeName);
        		
        	}
		}
		return processed;
	}


	private void processModelTypeName(JDefinedClass enumClass, String base, String fieldName, String typeName)
			throws ClassNotFoundException {
		if (fullNamesMap.containsKey(typeName)) {
			
			//if we have not reached the depth limit
			if (Collections.frequency(Lists.newArrayList(base.split("_")), fieldName) < depthLimit) {
				
				//compute all possibilities
				processFields(typeName, fullNamesMap.get(typeName).fields, enumClass, base + fieldName + "_");
				
			}
		}
	}
	
	
}
