pojo-metadata-generator
=======================

mojo to generate pojo meta data.<br>
Work In Progress.<br>
Use maven goal: <code>pmg:pmg</code> and add /target/pmg to your classpath.


	<repositories>
		<repository>
			<id>pojo-metadata-generator-mvn-repo</id>
			<url>https://raw.githubusercontent.com/antoinemeyer/pojo-metadata-generator/mvn-repo</url>
		</repository>
	</repositories>


		<dependency>
			<groupId>com.teketik</groupId>
			<artifactId>pmg-maven-plugin</artifactId>
			<version>1.11</version>
			<scope>compile</scope>
		</dependency>

	<build>
		<plugins>

			<plugin>
				<groupId>com.teketik</groupId>
				<artifactId>pmg-maven-plugin</artifactId>
				<configuration>
					<packages>
						<param>model.package</param>
					</packages>
				</configuration>
			</plugin>
			
  	</plugins>
	</build>
