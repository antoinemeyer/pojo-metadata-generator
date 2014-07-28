package com.teketik.pmg.metadata;

import java.io.Serializable;

public abstract class MetaData<T> implements Serializable {

	private Class<T> clazz;

	private String identifier;

	public MetaData(Class<T> clazz, String identifier) {
		super();
		this.clazz = clazz;
		this.identifier = identifier;
	}

	public Class<T> getClazz() {
		return clazz;
	}

	public void setClazz(Class<T> clazz) {
		this.clazz = clazz;
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

}
