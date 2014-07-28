pojo-metadata-generator
=======================

Generates pojo meta data.
Work In Progress.


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
