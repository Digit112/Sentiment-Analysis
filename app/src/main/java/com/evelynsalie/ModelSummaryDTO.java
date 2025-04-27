package com.evelynsalie;

import java.io.File;

public class ModelSummaryDTO {
	private boolean is_valid;
	
	private String name;
	private int num_lines_ingested;
	
	public ModelSummaryDTO(File model_file) {
		is_valid = true;
		
		name = model_file.getName();
		if (name.endsWith(".ekmd")) {
			name = name.substring(0, name.length() - 5);
		}
		else {
			is_valid = false;
			return;
		}
		
		num_lines_ingested = -1;
	}
	
	public String getJSON() {
		return "{\"name\":\"" + name + "\",\"num_ingested_lines\":\"" + num_lines_ingested + "\"}";
	}
	
	public boolean isValid() {
		return is_valid;
	}
}