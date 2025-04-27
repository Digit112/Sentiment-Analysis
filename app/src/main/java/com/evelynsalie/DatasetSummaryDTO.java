package com.evelynsalie;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;

public class DatasetSummaryDTO {
	private boolean is_valid;
	
	private String name;
	private int num_lines;
	
	public DatasetSummaryDTO(File model_file) {
		is_valid = true;
		
		name = model_file.getName();
		if (name.endsWith(".ekdt")) {
			name = name.substring(0, name.length() - 5);
		}
		else {
			is_valid = false;
			return;
		}
		
		try {
			BufferedReader data_scanner = new BufferedReader(new InputStreamReader(new FileInputStream(model_file)));
			num_lines = Integer.parseInt(data_scanner.readLine());
		}
		catch (IOException e) {
			is_valid = false;
			return;
		}
	}
	
	public String getJSON() {
		return "{\"name\":\"" + name + "\",\"num_lines\":\"" + num_lines + "\"}";
	}
	
	public boolean isValid() {
		return is_valid;
	}
}