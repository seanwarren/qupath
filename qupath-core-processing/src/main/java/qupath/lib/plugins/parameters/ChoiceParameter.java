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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Parameter that supports a list of choices.
 * 
 * May be displayed as a drop-down list.
 * 
 * @author Pete Bankhead
 *
 * @param <S>
 */
public class ChoiceParameter<S> extends AbstractParameter<S> {
	
	private static final long serialVersionUID = 1L;
	
	protected List<S> choices = null;

	ChoiceParameter(String group, String prompt, S defaultValue, List<S> choices, S lastValue, String helpText, boolean isHidden) {
		super(group, prompt, defaultValue, lastValue, helpText, isHidden);
		this.choices = choices;
	}

	public ChoiceParameter(String group, String prompt, S defaultValue, List<S> choices, S lastValue, String helpText) {
		this(group, prompt, defaultValue, choices, lastValue, helpText, false);
	}

	public ChoiceParameter(String group, String prompt, S defaultValue, List<S> choices, String helpText) {
		this(group, prompt, defaultValue, choices, null, helpText);
	}

	public ChoiceParameter(String group, String prompt, S defaultValue, S[] choices, String helpText) {
		this(group, prompt, defaultValue, Arrays.asList(choices), helpText);
	}

	public List<S> getChoices() {
		return choices;
	}

	@Override
	public S getStoredValue(){
		S defaultValue = getDefaultValue();
		if (defaultValue instanceof String)
			return (S) prefs.get(getPrompt(), defaultValue.toString());
		else
			return getDefaultValue();
	}

	@Override
	public boolean isValidInput(S value) {
		return choices.contains(value);
	}

	/**
	 * This will only work for string choices... for other types it will always return false
	 * and fail to set the lastValue
	 */
	@Override
	public boolean setStringLastValue(Locale locale, String value) {
		for (S choice : choices) {
			String choiceValue = choice.toString();
			if (choiceValue.equals(value)) {
				prefs.put(getPrompt(), choiceValue);
				return setValue(choice);
			}
		}
		return false;
//		try {
//			return setValue((S)value);
//		} catch (ClassCastException e) {
//			System.err.println(e.getLocalizedMessage());
//			e.printStackTrace();
//		}
//		return false;
	}

	@Override
	public Parameter<S> duplicate() {
		return new ChoiceParameter<>(getGroup(), getPrompt(), getDefaultValue(), getChoices(), getValue(), getHelpText(), isHidden());
	}

}
