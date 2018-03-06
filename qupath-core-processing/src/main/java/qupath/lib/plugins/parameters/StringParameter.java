/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.plugins.parameters;

import java.util.Locale;

/**
 * Parameter to represent a String value.
 * 
 * @author Pete Bankhead
 *
 */
public class StringParameter extends AbstractParameter<String> {
	
	private static final long serialVersionUID = 1L;

	StringParameter(String group, String prompt, String defaultValue, String lastValue, String helpText, boolean isHidden) {
		super(group, prompt, defaultValue, lastValue, helpText, isHidden);
	}

	public StringParameter(String group, String prompt, String defaultValue, String helpText) {
		this(group, prompt, defaultValue, null, helpText, false);
	}

	@Override
	protected String getStoredValue() {
		return prefs.get(getPrompt(), getDefaultValue());
	}

	@Override
	public boolean isValidInput(String value) {
		return value != null;
	}

	@Override
	public boolean setStringLastValue(Locale locale, String value) {
		prefs.put(getPrompt(), value);
		return setValue(value);
	}

	@Override
	public Parameter<String> duplicate() {
		return new StringParameter(getGroup(), getPrompt(), getDefaultValue(), getValue(), getHelpText(), isHidden());
	}

}
