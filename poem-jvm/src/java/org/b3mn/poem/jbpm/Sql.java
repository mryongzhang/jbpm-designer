package org.b3mn.poem.jbpm;

import java.io.StringWriter;

import org.json.JSONException;
import org.json.JSONObject;

public class Sql extends Node {

	protected String var;
	protected Boolean unique;
	protected String query;
	protected Parameters parameters;

	public Sql(JSONObject sql) {

		this.name = JsonToJpdl.readAttribute(sql, "name");
		this.var = JsonToJpdl.readAttribute(sql, "var");
		this.unique = new Boolean(JsonToJpdl.readAttribute(sql, "unique"));
		this.query = JsonToJpdl.readAttribute(sql, "query");
		try {
			this.parameters = new Parameters(sql.getJSONObject("properties")
					.getJSONObject("parameters"));
		} catch (JSONException e) {
			this.parameters = null;
		}
		
		this.bounds = JsonToJpdl.readBounds(sql);

		this.outgoings = JsonToJpdl.readOutgoings(sql);

	}

	public String getVar() {
		return var;
	}

	public void setVar(String var) {
		this.var = var;
	}

	public Boolean getUnique() {
		return unique;
	}

	public void setUnique(Boolean unique) {
		this.unique = unique;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	@Override
	public String toJpdl() throws InvalidModelException {
		StringWriter jpdl = new StringWriter();
		jpdl.write("<sql");

		jpdl.write(JsonToJpdl.transformAttribute("name", name));
		jpdl.write(JsonToJpdl.transformAttribute("var", var));
		if (unique != null)
			jpdl.write(JsonToJpdl.transformAttribute("unique", unique
					.toString()));

		if (bounds != null) {
			jpdl.write(bounds.toJpdl());
		} else {
			throw new InvalidModelException(
					"Invalid SQL activity. Bounds is missing.");
		}

		jpdl.write(" >\n");

		if (query != null) {
			jpdl.write("<query>\n");
			jpdl.write(query);
			jpdl.write("\n</query>\n");
		} else {
			throw new InvalidModelException(
					"Invalid SQL activity. Query is missing.");
		}
		
		if (parameters != null) {
			jpdl.write(parameters.toJpdl());
		}

		for (Transition t : outgoings) {
			jpdl.write(t.toJpdl());
		}

		jpdl.write("</sql>\n");

		return jpdl.toString();
	}

}