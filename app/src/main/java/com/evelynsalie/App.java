package com.evelynsalie;

import flak.annotations.Delete;
import flak.annotations.Post;
import flak.annotations.Route;
import flak.Flak;
import flak.Form;
import flak.Response;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.ClassLoader;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

public class App {
	/* ---------------- */
	/* Final Parameters */
	/* ---------------- */
	
	// How many reviews will be analyzed between sequence trie pruning steps.
	// The sequence trie takes up an enormous amount of memory and must be pruned regularly.
	// However, pruning too frequently reduces the accuracy of analysis.
	final private static int sequence_pruning_interval = 50000;
	
	// The maximum length of a sequence of tokens which can be understood by the model.
	// Memory usage is exponential in this parameter!
	final private static int max_token_sequence_length = 3;
		
	// The number of lines to use to sample the standard deviation of the model's outputs.
	// Used to adjust them to match the standard deviation of the actual labels.
	final private static int num_renormalization_lines = 8000;
	
	// Threshold after which expired bearer tokens will begin getting pruned.
	// Only expired tokens will be pruned, even if the threshold is exceeded.
	private final static int preferred_max_active_tokens = 50;
	
	// Lifetime, in seconds, of bearer tokens distributed to users.
	private final static int bearer_token_lifetime = 3600 * 2;
	
	/* ----- */
	/* State */
	/* ----- */
	
	// The template, loaded from the resources.
	// Serverd (with varying content) whenever a GET request for a webpage is received.
	private static String template;
	
	// Tracks valid tokens obtained via login.
	private static HashMap<String, BearerToken> valid_tokens;
	
	// Tracks models currently loaded in memory.
	private static HashMap<String, Model> loaded_models;
	
	/* --------- */
	/* Utilities */
	/* --------- */
	
	// Gets the number of lines from the passed file.
	public static int getLinesCount(File file) throws FileNotFoundException, IOException {
		int num_lines = 0;
		
		BufferedReader data_scanner = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
		String data = "";
		while (data != null) {
			data = data_scanner.readLine();
			assert data.length() > 0 : num_lines;
			num_lines++;
			
			if (num_lines % 300000 == 0) System.out.print(".");
		}
		System.out.println("");
		
		data_scanner.close();
		return num_lines;
	}
	
	// Kill the stream buffering god which surely does not exist for a good reason.....
	public static String inputStreamToString(InputStream input) throws IOException {
		int buffer_size = 1024;
		char[] buffer = new char[buffer_size];
		
		StringBuilder out = new StringBuilder();
		InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
		for (int numRead; (numRead = reader.read(buffer, 0, buffer.length)) > 0; ) {
			out.append(buffer, 0, numRead);
		}
		
		reader.close();
		input.close();
		return out.toString();
	}
	
	/* -------------- */
	/* Web Page Utils */
	/* -------------- */
	
	// Dead simple (that is, not recursive) template engine.
	// Searches for tokens of the form %%tokenname%% in the passed input string.
	// For each, if the content between the double percent signs matches a key in the passed map,
	// replace the whole token with the key's associated value in the map.
	// Substituted text will not, itself, be checked for keys.
	public void renderTemplateToResponse(String input, Map<String, String> map, Response response) throws IOException {
		OutputStreamWriter osw = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
		
		int prev_token_end = 0;
		int next_token_start = input.indexOf("%%", prev_token_end);
		while (next_token_start != -1) {
			int key_end = input.indexOf("%%", next_token_start+2);
			
			if (key_end != -1) {
				String key = input.substring(next_token_start+2, key_end);
				if (map.containsKey(key)) {
					// Text between two token delimiters is a key. Perform substitution.
					osw.write(input.substring(prev_token_end, next_token_start));
					osw.write(map.get(key));
				}
				else {
					// Text between token delimiters is not a key.
					osw.write(input.substring(prev_token_end, key_end+2));
				}
				prev_token_end = key_end+2;
			}
			else {
				// Token delimiter not closed.
				osw.write(input.substring(prev_token_end, next_token_start+2));
				prev_token_end = next_token_start+2;
			}
			
			next_token_start = input.indexOf("%%", prev_token_end);
		}
		
		osw.write(input.substring(prev_token_end));
		
		osw.close();
	}
	
	// Appends the unmodified text to the passed response.
	public void renderStaticPageToResponse(String input, Response response) throws IOException {
		OutputStreamWriter osw = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
		osw.write(input);
		osw.close();
	}
	
	// Returns true if the passed response contains a valid bearer token. False otherwise.
	public boolean isCredentialed(Response response) {
		String tk = response.getRequest().getCookie("tk");
		System.out.println("Credential: " + tk);
		if (valid_tokens.containsKey(tk) && !valid_tokens.get(tk).isExpired()) {
			return true;
		}
		else {
			valid_tokens.remove(tk);
			return true; //false;
		}
	}
	
	// Removes all expired tokens. Called automatically when more than preferred_max_active_tokens tokens afre active after a new token is added.
	public void pruneExpiredTokens() {
		HashMap<String, BearerToken> new_valid_tokens = new HashMap<String, BearerToken>();
		for (Map.Entry<String, BearerToken> pair : valid_tokens.entrySet()) {
			if (!pair.getValue().isExpired()) new_valid_tokens.put(pair.getKey(), pair.getValue());
		}
		
		valid_tokens = new_valid_tokens;
	}
	
	// Checks if the requesting user is authenticated.
	// If not, redirect them to the login page with a query string so that they may return after authentication.
	public boolean contemplateRedirect(Response response) {
		if (!isCredentialed(response)) {
			String encoded_query = Base64.getEncoder().encodeToString(
				response.getRequest().getPath().getBytes(StandardCharsets.UTF_8)
			);
			
			response.redirect("/login?goal=" + encoded_query);
			return true;
		}
		else {
			return false;
		}
	}
	
	/* --------------- */
	/* Web Pages & API */
	/* --------------- */
	
	// Homepage
	@Route("/")
	public void index(Response response) throws IOException {
		if (contemplateRedirect(response)) return;
		
		response.addHeader("Content-Type", "text/html; charset=utf-8");
		response.setStatus(200);
		
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("content", inputStreamToString(App.class.getClassLoader().getResourceAsStream("index.html")));
		renderTemplateToResponse(template, map, response);
	}
	
	// Stylesheet
	@Route("/css")
	public void css(Response response) throws IOException {
		response.addHeader("Content-Type", "text/css; charset=utf-8");
		response.setStatus(200);
		
		renderStaticPageToResponse(inputStreamToString(App.class.getClassLoader().getResourceAsStream("index.css")), response);
	}
	
	// Login page
	@Route("/login")
	public void login(Response response) throws IOException {
		response.addHeader("Content-Type", "text/html; charset=utf-8");
		response.setStatus(200);
		
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("content", inputStreamToString(App.class.getClassLoader().getResourceAsStream("login.html")));
		renderTemplateToResponse(template, map, response);
	}
	
	@Route("/model-selection")
	public void model_selection(Response response) throws IOException {
		if (contemplateRedirect(response)) return;
		
		response.addHeader("Content-Type", "text/html; charset=utf-8");
		response.setStatus(200);
		
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("content", inputStreamToString(App.class.getClassLoader().getResourceAsStream("model-selection.html")));
		renderTemplateToResponse(template, map, response);
	}
	
	@Route("/model-creation")
	public void model_creation(Response response) throws IOException {
		if (contemplateRedirect(response)) return;
		
		response.addHeader("Content-Type", "text/html; charset=utf-8");
		response.setStatus(200);
		
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("content", inputStreamToString(App.class.getClassLoader().getResourceAsStream("model-creation.html")));
		renderTemplateToResponse(template, map, response);
	}
	
	@Route("/data-labeling")
	public void data_labeling(Response response) throws IOException {
		if (contemplateRedirect(response)) return;
		
		response.addHeader("Content-Type", "text/html; charset=utf-8");
		response.setStatus(200);
		
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("content", inputStreamToString(App.class.getClassLoader().getResourceAsStream("data-labeling.html")));
		renderTemplateToResponse(template, map, response);
	}
	
	// Login credential validation
	@Route("api/authenticate")
	@Post
	public void login_post(Response response) throws IOException, FileNotFoundException {
		String userpass = response.getRequest().getHeader("Authorization");
		File logins_fin = new File("logins");
		
		boolean is_authenticated = false;
		System.out.println("Authenticating with '" + userpass + "'");
		
		BufferedReader brin = new BufferedReader(new FileReader(logins_fin));
	
		String line;
		while ((line = brin.readLine()) != null) {
			if (line.equals(userpass)) {
				is_authenticated = true;
				break;
			}
		}
		
		brin.close();
		
		if (is_authenticated) {
			// Redirect to original goal.
			String new_path = "/";
			
			String encodedString = response.getRequest().getQuery().get("goal");
			System.out.println("Encoded: " + encodedString);
			if (encodedString != null) {
				byte[] decodedBytes = Base64.getDecoder().decode(encodedString);
				new_path = new String(decodedBytes, StandardCharsets.UTF_8);
				System.out.println(new_path);
			}
			
			// Generate and assign a token.
			BearerToken new_token = new BearerToken(bearer_token_lifetime);
			valid_tokens.put(new_token.toString(), new_token);
			response.addHeader("Set-Cookie", "tk=" + new_token.toString() + "; Path=/");
			
			// Redirect
			System.out.println("Redirecting to '" + new_path + "'");
			response.addHeader("Content-Type", "text/plain; charset=utf-8");
			response.addHeader("Location", new_path);
			response.setStatus(200);
		}
		else {
			// Redirect to login with error message.
			response.addHeader("Content-Type", "text/plain; charset=utf-8");
			response.addHeader("Location", "/login?error=true");
			response.setStatus(200);
		}
	}
	
	@Route("/api/models")
	public void get_models(Response response) throws IOException {
		File models_dir = new File("models");
		File[] models = models_dir.listFiles();
		
		String data = "[";
		boolean skipped_one = false;
		for (int i = 0; i < models.length; i++) {
			ModelSummaryDTO dto = new ModelSummaryDTO(models[i]);
			
			if (dto.isValid()) {
				if (skipped_one) {
					data += ",";
				}
				else {
					skipped_one = true;
				}
				
				data += dto.getJSON();
			}
		}
		data += "]";
		
		response.addHeader("Content-Type", "application/json; charset=utf-8");
		response.setStatus(200);
		renderStaticPageToResponse(data, response);
	}
	
	@Route("/api/models")
	@Delete
	public void delete_models(Response response) throws IOException {
		String filename = "models/" + inputStreamToString(response.getRequest().getInputStream()) + ".ekmd";
			
		File model = new File(filename);
		
		if (model.exists()) {
//			System.out.println("Deleteing '" + filename + "'");
			model.delete();
			response.setStatus(200);
		}
		else {
//			System.out.println("'" + filename + "' Not Found.");
			response.setStatus(404);
		}
	}
	
	@Route("/api/models")
	@Post
	public void put_models(Response response) throws IOException {
		String input_string = inputStreamToString(response.getRequest().getInputStream());
		String[] inputs = input_string.split("/");
		if (inputs.length != 4) {
			response.setStatus(400);
			return;
		}
		
		File model_file = new File("models/" + inputs[0] + ".ekmd");
		File dataset_file = new File("labeled-data/" + inputs[1] + ".ekdt");
		int min_token_occurence = Integer.parseInt(inputs[2]);
		int num_lines = Integer.parseInt(inputs[3]);
		
		// Check parameter validity
		if (min_token_occurence < 0 || num_lines < 1) {
			response.setStatus(400);
			return;
		}
		
		// Check that the dataset exists.
		if (!dataset_file.exists()) {
			response.setStatus(400);
			return;
		}
		
		// Do not overwrite existing model.
		if (model_file.exists()) {
			response.setStatus(400);
			return;
		}
		
		Model model = new Model(
			max_token_sequence_length, min_token_occurence,
			sequence_pruning_interval, num_renormalization_lines
		);
		loaded_models.put(inputs[0], model);
		
		model.buildFromFile(dataset_file, num_lines, 0, 0);
		
		try {
			model.saveToFile(model_file);
			System.out.println("Model Recorded.");
		}
		catch (IOException e) {
			response.setStatus(400);
			return;
		}
	}
	
	@Route("/api/model-progress")
	public void get_model_progress(Response response) throws IOException {
		String model_name = response.getRequest().getQuery().get("model-name");
		response.addHeader("Content-Type", "application/json; charset=UTF-8");
		
		// Model name not specified, bad request.
		if (model_name == null) {
			response.setStatus(400);
			return;
		}
		
		// Check if the model is done.
		File model = new File("models/" + model_name + ".ekmd");
		if (model.exists()) {
			renderStaticPageToResponse(Model.getCompleteStatusJSON(), response);
			return;
		}
		
		// Model not found.
		if (!loaded_models.containsKey(model_name)) {
			response.setStatus(404);
			return;
		}
		
		// Model loaded but not saved to disk.
		// Thus the model must be in progress of being built.
		renderStaticPageToResponse(loaded_models.get(model_name).getStatusJSON(), response);
	}
	
	@Route("/api/datasets")
	public void get_datasets(Response response) throws IOException {
		File labeled_data_dir = new File("labeled-data");
		File[] datasets = labeled_data_dir.listFiles();
		
		String data = "[";
		boolean skipped_one = false;
		for (int i = 0; i < datasets.length; i++) {
			DatasetSummaryDTO dto = new DatasetSummaryDTO(datasets[i]);
			
			if (dto.isValid()) {
				if (skipped_one) {
					data += ",";
				}
				else {
					skipped_one = true;
				}
				
				data += dto.getJSON();
			}
		}
		data += "]";
		
		response.addHeader("Content-Type", "application/json; charset=utf-8");
		response.setStatus(200);
		renderStaticPageToResponse(data, response);
	}
	
	// Retrieve Labels for the supplied line(s).
	@Route("/api/labels")
	@Post
	public void get_labels(Response response) throws IOException {
		String content_type = response.getRequest().getHeader("Content-Type");
		if (content_type == null) {
			response.setStatus(400);
			return;
		}
		
		// Acquire model for labeling.
		String model_name = response.getRequest().getCookie("current-model");
		if (model_name == null) {
			response.setStatus(400);
			return;
		}
		
		Model model;
		if (loaded_models.containsKey(model_name)) {
			model = loaded_models.get(model_name);
		}
		else {
			File file = new File("models/" + model_name + ".ekmd");
			if (!file.exists()) {
				response.setStatus(400);
				return;
			}
			else {
				model = new Model(file);
			}
		}
		
		// Should be impossible at this point.
		if (model == null) {
			response.setStatus(400);
			return;
		}
		
		String[] statements = null;
		// Single plaintext statement.
		if (content_type.startsWith("text/plain")) {
			statements = new String[1];
			statements[0] = inputStreamToString(response.getRequest().getInputStream());
		}
		// File of newline-delimited statemetns.
		else if (content_type.startsWith("multipart/form-data")) {
			// Get the formdata boundary
			int boundary_start = content_type.indexOf("boundary");
			if (boundary_start == -1) {
				response.setStatus(400);
				return;
			}
			
			boundary_start = content_type.indexOf("=", boundary_start+8) + 1;
			if (boundary_start == -1) {
				response.setStatus(400);
				return;
			}
			
			int boundary_end = Math.max(content_type.indexOf(";", boundary_start), content_type.length());
			String boundary = "--" + content_type.substring(boundary_start, boundary_end);
			
			// Get the rawtext body of the submission.
			String[] form_components = inputStreamToString(response.getRequest().getInputStream()).split(boundary);
			
			// Note we skip the first form component, an empty string before the first boundary.
			for (int i = 1; i < form_components.length; i++) {
				String form_component = form_components[i];
				
				// Check for the two-hyphen suffix for the final boundary.
				if (form_component.startsWith("--")) break;
				
				// Split header and body.
				int header_end = form_component.indexOf("\r\n\r\n");
				if (header_end == -1) {
					response.setStatus(400);
					return;
				}
				
				String header = form_component.substring(0, header_end);
				String body = form_component.substring(header_end + 4);
				
				// Get name param;
				int name_start = header.indexOf("name");
				if (name_start == -1) {
					response.setStatus(400);
					return;
				}
				
				name_start = header.indexOf("\"", name_start+4) + 1;
				if (name_start == -1) {
					response.setStatus(400);
					return;
				}
				
				int name_end = Math.min(header.indexOf("\"", name_start), header.length());
				
				String name = header.substring(name_start, name_end);
				
				// Check for and return file param.
				if (name.equals("file")) {
					statements = body.split("\\r?\\n");
					break;
				}
			}
		}
		else {
			response.setStatus(400);
			return;
		}
		
		if (statements == null) {
			response.setStatus(400);
			return;
		}
		
		String[] pos_examples = new String[5];
		String[] neg_examples = new String[5];
		double[] pos_labels = new double[5];
		double[] neg_labels = new double[5];
		
		for (int i = 0; i < 5; i++) {
			pos_examples[i] = null;
			neg_examples[i] = null;
			pos_labels[i] = -2;
			neg_labels[i] = 2;
		}
		
		// Obtain labels. Record statistics and most positive/negative 5 statements.
		StatisticsTracker tracker = new StatisticsTracker(1000, -2, 2);
		for (int i = 0; i < statements.length; i++) {
			String statement = statements[i];
			double label = model.getLabel(statement);
			
			tracker.addValue(label);
			
			// Insert into sorted array of 5 most positive statements.
			for (int j = 0; j < 5; j++) {
				if (pos_examples[j] == null) {
					pos_examples[j] = statement;
					pos_labels[j] = label;
					break;
				}
				else if (label > pos_labels[j]) {
					// Insert value by shifting elements. Last element is overwritten.
					for (int k = 4; k > j; k--) {
						pos_examples[k] = pos_examples[k-1];
						pos_labels[k] = pos_labels[k-1];
					}
					
					pos_examples[j] = statement;
					pos_labels[j] = label;
					break;
				}
			}
			
			// Insert into sorted arrray of 5 most negative statements.
			for (int j = 0; j < 5; j++) {
				if (neg_examples[j] == null) {
					neg_examples[j] = statement;
					neg_labels[j] = label;
					break;
				}
				else if (label < neg_labels[j]) {
					// Insert value by shifting elements. Last element is overwritten.
					for (int k = 4; k > j; k--) {
						neg_examples[k] = neg_examples[k-1];
						neg_labels[k] = neg_labels[k-1];
					}
					
					neg_examples[j] = statement;
					neg_labels[j] = label;
					break;
				}
			}
		}
		
		// Construct JSON output.
		String data = "{\"stats\": " + new StatisticsTrackerDTO(tracker).getJSON() + ", \"pos-examples\": [";
		
		for (int i = 0; i < 5; i++) {
			data += "{\"statement\":\"" + pos_examples[i] + "\", \"label\":" + String.format("%.2f", pos_labels[i]) + "}";
			if (i < 4) data += ", ";
		}
		
		data += "], \"neg-examples\": [";
		
		for (int i = 0; i < 5; i++) {
			data += "{\"statement\":\"" + neg_examples[i] + "\", \"label\":" + String.format("%.2f", neg_labels[i]) + "}";
			if (i < 4) data += ", ";
		}
		
		data += "]}";
		
		// Write response.
		response.addHeader("Content-Type", "application/json; charset=UTF-8");
		response.setStatus(200);
		renderStaticPageToResponse(data, response);
	}
	
	public static void main(String[] args) throws FileNotFoundException, IOException, Exception {
		// Retrieve the template that all page bodies are plugged into.
		template = inputStreamToString(App.class.getClassLoader().getResourceAsStream("template.html"));
		
		// Map of authentication tokens which have been distributed.
		valid_tokens = new HashMap<String, BearerToken>();
		
		// Map of models currently loaded in memory.
		// Models are loaded upon their creation or utilization.
		loaded_models = new HashMap<String, Model>();
		
		// Create default logins file if it does not exist.
		File logins_file = new File("logins");
		if (logins_file.createNewFile()) {
			OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(logins_file), StandardCharsets.UTF_8);
			osw.write("admin/password");
			osw.close();
		}
		
		// Create directory for storing models if it does not already exist.
		File models_dir = new File("models");
		models_dir.mkdir();
		
		// Create directory for storing training data.
		File labeled_data_dir = new File("labeled-data");
		labeled_data_dir.mkdir();
		
		// Begin the flak daemon.
		flak.App app = Flak.createHttpApp(8080);
		app.scan(new App());
		app.start();
	}
	
	/* --------------- */
	/* Model Wrangling */
	/* --------------- */
	
	// Perform cross-validation on the given number of lines from the given file, divided into k folds, and return the combined results.
	// Forwards relevant arguments to the model constructor.
	public static ModelTestResults crossValidate(
		File training_data, int num_lines, int num_folds,
		int max_token_sequence_length, int min_token_occurence, int num_renormalization_lines
	) throws FileNotFoundException, IOException {
		ModelTestResults results = new ModelTestResults(200);
		
		for (int i = 0; i < num_folds; i++) {
			// Build temporary model.
			Model model = new Model(max_token_sequence_length, min_token_occurence, sequence_pruning_interval, num_renormalization_lines);
			model.buildFromFile(training_data, num_lines, num_folds, i);
			
			ModelTestResults fold_results = model.testOnLines(training_data, num_lines, num_folds, i);
			results.integrateNewResults(fold_results);
		}
		
		return results;
	}
	
	public static void stuff() throws FileNotFoundException, IOException, Exception {
		File folder = new File(".");
		System.out.println("In '" + folder.getPath() + "'");
		File[] listOfFiles = folder.listFiles();
		if(listOfFiles != null) {
			for (int i = 0; i < listOfFiles.length; i++) {
				if (listOfFiles[i].isFile()) {
					System.out.println("File " + listOfFiles[i].getName());
				} else if (listOfFiles[i].isDirectory()) {
					System.out.println("Directory " + listOfFiles[i].getName());
				}
			}
		}
		
		// The minimum number of times a token must appear in the data set to be considered
		// a real, trackable word.
		final int min_token_occurence = 400;
		
		File training_data = new File("training_data.txt");
		File model_save_file = new File("model.ekmd");
		
		// Note that the training data actually contains 6990280 lines.
		//System.out.println(getLinesCount(file));
		
		// Parameters for cross-validation.
		// Lines beyond validation_lines will not be used.
		int validation_lines = 100000;//6990280;
		int cross_validation_k = 5;
		
		ModelTestResults test_results = crossValidate(
			training_data, validation_lines, cross_validation_k,
			max_token_sequence_length, min_token_occurence, num_renormalization_lines
		);
		
		System.out.println(test_results);
		
		System.out.println("Act. Labels:");
		test_results.getLabelStats().printHistogram(20);
		
		System.out.println("Gen. Labels:");
		test_results.getResultStats().printHistogram(20);
		
		assert validation_lines % cross_validation_k == 0 : "Number of lines for use in k-fold cross-validation must be divisible by k.";
		
		Model model = new Model(max_token_sequence_length, min_token_occurence, sequence_pruning_interval, num_renormalization_lines);
		model.buildFromFile(training_data, validation_lines, 0, 0);
		
		model.saveToFile(model_save_file);
		System.out.println(model.getLabel("i highly recommend it everyone was very kind and considerate the whole family had great fun"));
		System.out.println(model.getLabel("quite frankly i'm disturbed the place is nasty and the staff are rude"));
		System.out.println(model.getLabel("eye-catching decor and the staff are friendly but the bathrooms are not well kept not my favorite place"));
		System.out.println("");
		
		System.out.println("Loading saved model...");
		model = new Model(model_save_file);
		System.out.println(model.getLabel("i highly recommend it everyone was very kind and considerate the whole family had great fun"));
		System.out.println(model.getLabel("quite frankly i'm disturbed the place is nasty and the staff are rude"));
		System.out.println(model.getLabel("eye-catching decor and the staff are friendly but the bathrooms are not well kept not my favorite place"));
		System.out.println("");
		//System.out.println(model.getLabel("what the fuck did you just fucking say about me you little bitch i'll have you know i graduated top of my class in the navy seals and i've been involved in numerous secret raids on al-quaeda and i have over 300 confirmed kills i am trained in gorilla warfare and i'm the top sniper in the entire US armed forces you are nothing to me but just another target i will wipe you the fuck out with precision the likes of which has never been seen before on this earth mark my fucking words you think you can get away with saying that shit to me over the internet think again fucker as we speak i am contacting my secret network of spies across the usa and your ip is being traced right now so you better prepare for the storm maggot the storm that wipes out the pathetic little thing you call your life you're fucking dead kid i can be anywhere anytime and i can kill you in over seven hundred ways and that's just with my bare hands not only am i extensively trained in unarmed combat but i have access to the entire arsenal of the united states marine corps and i will use it to its full extent to wipe your miserable ass off the face of the continent you little shit if only you could have known what unholy retribution your little 'clever' comment was about to bring down upon you maybe you would have held your fucking tongue but you couldn't you didn't and now you're paying the price you goddamn idiot i will shit fury all over you and you will drown in it you're fucking dead kiddo"));		
		System.out.println("\n" + model.getAllScoreMean());
	}
}