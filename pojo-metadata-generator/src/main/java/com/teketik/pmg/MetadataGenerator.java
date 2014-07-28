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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

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
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JEnumConstant;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;
import com.teketik.pmg.metadata.FieldMetaData;
import com.teketik.pmg.metadata.FirstDegreeMetadata;
import com.teketik.pmg.metadata.MetaData;
import com.teketik.pmg.metadata.ModelMetaData;

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

	private JDefinedClassHolder definedClassHolder;

	private JDefinedClass _class;
	
	JCodeModel cm = new JCodeModel();

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

			//fill the missing packages in the full names map
			Set<Entry<String, String>> simpleNameToFullNameEntrySet = simpleNameToFullName.entrySet();
			for (Entry<String, String> entry : simpleNameToFullNameEntrySet) {
				fullNamesMap.put(entry.getValue(), map.get(entry.getKey()));
			}
			
			//now write all the model files
			for (Entry<String, String> entry : simpleNameToFullNameEntrySet) {
				process(packageName, entry.getKey(), entry.getValue(), map.get(entry.getKey()).fields);
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

	private class JDefinedClassHolder {
		private JDefinedClass modelFD;
		private JDefinedClass fieldFD;
		private JDefinedClass model;
		private JDefinedClass field;
	}
	
	public void process(String packageName, String simpleName, String clazz, List<FieldDeclaration> fields) throws MojoExecutionException {
		definedClassHolder = new JDefinedClassHolder();
		try {
			//write the entity model first degree metadata
			definedClassHolder.modelFD = writeMetadataClass(packageName, simpleName+"ModelFirstDegreeMetaData", cm.ref(ModelMetaData.class), cm.ref(FirstDegreeMetadata.class));
			//write the entity field first degree metadata
			definedClassHolder.fieldFD =writeMetadataClass(packageName, simpleName+"FieldFirstDegreeMetaData", cm.ref(FieldMetaData.class), cm.ref(FirstDegreeMetadata.class));
			//write the entity model metadata
			definedClassHolder.model = writeMetadataClass(packageName, simpleName+"ModelMetaData", cm.ref(ModelMetaData.class));
			//write the entity field metadata
			definedClassHolder.field = writeMetadataClass(packageName, simpleName+"FieldMetaData", cm.ref(FieldMetaData.class));
			
			//then write the metadata itself
			_class = cm._class(clazz+"MetaData");
			
	        //then process all the fields
	        boolean processed = processFields(clazz, fields, "");

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


	private JDefinedClass writeMetadataClass(String packageName, String className, JClass... interfaces) throws JClassAlreadyExistsException, ClassNotFoundException {
		JDefinedClass asgasga = cm._class(packageName+".metadata."+className)._extends(cm.ref(MetaData.class).narrow(cm.parseType("T")));
		asgasga.generify("T");
		//process implementations
		for (JClass jClass : interfaces) {
			asgasga._implements(jClass.narrow(cm.parseType("T")));
		}
		//create constructor
		JMethod enumConstructor = asgasga.constructor(JMod.PUBLIC);
		enumConstructor.param(cm.ref(Class.class).narrow(cm.parseType("T")), "clazz");
		enumConstructor.param(cm.ref(String.class), "identifier");
		enumConstructor.body().invoke("super").arg(JExpr.ref("clazz")).arg(JExpr.ref("identifier"));
		return asgasga;
	}


	private void generateEnum(JDefinedClass enumClass) {
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
		
		//also override the toString() method to return the value with dots instead of _
		JMethod toStringMethod = enumClass.method(JMod.PUBLIC, String.class, "toString");
		toStringMethod.body()._return(JExpr.direct("name().replace(\"_\",\".\")"));
	}

	private boolean processFields(String clazz, List<FieldDeclaration> fields, String base) throws ClassNotFoundException {
		boolean processed = false;
		for (FieldDeclaration field : fields) {

        	//if not static
    		ClassOrInterfaceType type = (ClassOrInterfaceType)field.getType();
    		if (!Modifier.isStatic(field.getModifiers())) {

        		String fieldName = field.getVariables().get(0).getId().getName();
				String typeName = type.getName();
				String fullName = base + fieldName;

				getLog().info("generating field: " + fullName);
        		processed = true;
        		
        		//define to which class this field belongs to
        		JDefinedClass varType;
        		boolean isFirstDegree = isFirstDegree(base);
        		if (isModel(type)) {
        			varType = isFirstDegree ? definedClassHolder.modelFD : definedClassHolder.model;
        		} else {
        			varType = isFirstDegree ? definedClassHolder.fieldFD : definedClassHolder.field;
        		}
        		

        		boolean toReify = false;
        		JClass boxify = cm.parseType(typeName).boxify();
        		
        		//if this field is a collection
        		//process type args
        		List<String> deferedTypeArgNames = Lists.newArrayList();
        		if (type.getTypeArgs() != null) {
        			for (Type typeArg : type.getTypeArgs()) {
        				String typeArgName = getTypeName(typeArg);
        				deferedTypeArgNames.add(typeArgName);
        				boxify = boxify.narrow(cm.parseType(typeArgName));
        				toReify = true;
        			}
        		}
        		
        		//write it
				JClass narrowedVartype = varType.narrow(boxify);
				JFieldVar enumConst = _class.field(JMod.PUBLIC|JMod.STATIC|JMod.FINAL, narrowedVartype, fullName);
        		JExpression direct = JExpr.direct(typeName+".class");
        		//cast it if reified
        		if (toReify) {
        			direct = JExpr.cast(cm.parseType("Class").boxify().narrow(cm.wildcard()), direct);
        			direct = JExpr.cast(cm.parseType("Class").boxify().narrow(boxify), direct);
        		}
        		enumConst.init(JExpr._new(narrowedVartype).arg(direct).arg(JExpr.direct("\""+fullName.replaceAll("_", ".")+"\"")));
        		

        		//process defered type args
        		for (String typeArgName : deferedTypeArgNames) {
    				processModelTypeName(base, fieldName, typeArgName);
				}
        		
				//process eventual subtypes
				processModelTypeName(base, fieldName, typeName);
        		
        	}
		}
		return processed;
	}

	private boolean isFirstDegree(String base) {
		return base.equals("");
	}

	private boolean isModel(ClassOrInterfaceType type) {
		if (type.getTypeArgs() != null) {
			for (Type typeArg : type.getTypeArgs()) {
				String typeArgName = getTypeName(typeArg);
				if (fullNamesMap.containsKey(typeArgName)) {
					return true;
				}
			}
		} 
		if (fullNamesMap.containsKey(getTypeName(type))) {
			return true;
		} else {
			return false;
		}
	}


	private void processModelTypeName(String base, String fieldName, String typeName)
			throws ClassNotFoundException {
		if (fullNamesMap.containsKey(typeName)) {
			
			//if we have not reached the depth limit
			if (Collections.frequency(Lists.newArrayList(base.split("_")), fieldName) < depthLimit) {
				
				//compute all possibilities
				processFields(typeName, fullNamesMap.get(typeName).fields, base + fieldName + "_");
				
			}
		}
	}
	
	
}
