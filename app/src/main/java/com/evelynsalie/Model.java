package com.evelynsalie;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
/**
* Represents a language processing model capable of labeling text on a continuous scale from negative (-1) to positive (1).
* Maintains a dictionary of encountered tokens and sequences of tokens,
* and statistics regarding the positivity of negativity of those tokens.
*/
public class Model {
	// The root node of the TokenSequence prefix tree
	// This tracks all encountered token sequences up to the length specified in the constructor.
	private TokenSequence root;
	
	// Dictionary of all encountered tokens
	private TokenDictionary all_tokens;
	
	private int num_token_sequences;
	private int num_lines_analyzed;
	
	// This value is set after training and is used to adjust the distribution of the outputs
	// to more closely match that of the training data.
	private double gen_labels_mul;
	private double gen_labels_off;
	
	private final int max_token_sequence_length;
	private final int min_token_occurence;
	private final int sequence_pruning_interval;
	private final int num_output_renormalization_samples;
	
	private final static double POLARITY_BIAS_EXP = 1;
	private final static double DEVIATION_BIAS_EXP = 3;
	
	private enum Stage {
		INIT, WORDS, PHRASES, RENORMALIZING, COMPLETE
	};
	
	// Used to track status during training.
	private double status_percent;
	private Stage status_stage;
	
	/**
	* Create an empty model.
	* @param max_token_sequence_length The maximum length of sequences of tokens whose scores are trackeed.
	* @param min_token_occurence The minimum number of times that a token can appear in the training data for it to not be pruned.
	* @param sequence_pruning_interval The number of reviews analyzed between sequence trie pruning steps.
	* @param num_output_renormalization_samples The number of lines to label, after constructing the model, to use for renormalizing the model's output to match the distribution of training data.
	*/
	public Model(
		int max_token_sequence_length, int min_token_occurence,
		int sequence_pruning_interval, int num_output_renormalization_samples
	) {
		root = new TokenSequence(max_token_sequence_length, null, null);
		this.all_tokens = new TokenDictionary();
		
		this.num_token_sequences = 0;
		this.num_lines_analyzed = 0;
		
		this.gen_labels_mul = 1;
		this.gen_labels_off = 0;
		
		this.max_token_sequence_length = max_token_sequence_length;
		this.min_token_occurence = min_token_occurence;
		this.sequence_pruning_interval = sequence_pruning_interval;
		this.num_output_renormalization_samples = num_output_renormalization_samples;
		
		this.status_stage = Stage.INIT;
		this.status_percent = 0;
	}
	
	/**
	* Load a previously saved model from file.
	* @throws FileNotFoundException When the passed file cannot be found.
	* @throws IOException When an IO error occurs while reading the file.
	* @throws IllegalArgumentException If the file is not a valid ekmd file.
	*/
	public Model(File file) throws FileNotFoundException, IOException, IllegalArgumentException {
		DataInputStream din = new DataInputStream(new FileInputStream(file));
		
		String iden = "";
		for (int i = 0; i < 8; i++) iden += (char) din.read();
		if (!iden.equals("EkoModel")) {
			din.close();
			throw new IllegalArgumentException("File is not a valid .ekmd file. Should have signature 'EkoModel' but has '" + iden + "'");
		}
		
		this.num_token_sequences = din.readInt();
		this.num_lines_analyzed = din.readInt();
		
		this.gen_labels_mul = din.readDouble();
		this.gen_labels_off = din.readDouble();
		
		this.max_token_sequence_length = din.readInt();
		this.min_token_occurence = din.readInt();
		this.sequence_pruning_interval = din.readInt();
		this.num_output_renormalization_samples = din.readInt();
		
		// Read all tokens from file.
		this.all_tokens = new TokenDictionary(din);
		
		// Read all TokenSequences from file.
		root = new TokenSequence(din, null, all_tokens);
		
		din.close();
	}
	
	/**
	* Gets the status of a completed model.
	* @return The same value that would be returned by a call to {@link #getStatusJSON()} on a finalized model.
	*/
	public static String getCompleteStatusJSON() {
		return "{\"stage\":\"" + Model.getStageName(Stage.COMPLETE) + "\"}";
	}
	
	/**
	* Standard method for sanitizing text.
	* Converts all letters to lowercase, trims the text, and replaces all sequences of unacceptable characters with a single space.
	* Acceptable characters include english letters, numbers, the apostrophe and hyphen.
	*/
	public static String sanitize(String raw) {
		raw = raw.trim().toLowerCase();
		
		String ret = "";
		boolean do_space = false;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '\'') {
				do_space = true;
				ret += c;
			}
			else if (do_space) {
				ret += " ";
				do_space = false;
			}
		}
		
		return ret;
	}
	
	/**
	* Gets a JSON string representing the status of this model as it is built in another thread.
	* @return A JSON string containing a "stage" key, whose value is one of: "initialization", "words", "phrases", "renormalization", or "complete". An optional "progress" key containing a number between 0 and 1 may also signify the fraction of the current stage which is complete.
	*/
	public String getStatusJSON() {
		if (status_stage == Stage.COMPLETE) {
			return Model.getCompleteStatusJSON();
		}
		else {
			return "{\"stage\":\"" + Model.getStageName(status_stage) + "\",\"progress\":" + String.format("%.2f", status_percent) + "}";
		}
	}
		
	/**
	* Creates and retains a Token object for every unique (whitespace-delimited) character sequence in the passed string.
	* Will not create new Token entities for previously seen words. Tracks the number of occurences of the encounteredx words.
	* @param line The raw text to learn from.
	*/
	public void learnTokens(String line) {
		all_tokens.learnTokens(line);
	}
	
	/**
	* Converts a raw string into an array of tokens.
	* Returned Token objects must have been previously created by a call to learnTokens()
	* If a character sequence is not recognized, a corresponding entry in the returned array will be null.
	* Individual tokens do not have tracked stats or statistics, except the number of occurences.
	* TokenSequence objects (which may consist of a single Token) do have statistics.
	* @param line The raw text to tokenize.
	* @return An array of Token objects. Typically used to obtain TokenSequence object(s).
	*/
	public ArrayList<Token> tokenize(String line) {
		return all_tokens.tokenize(line);
	}
	
	/**
	* Returns the token sequence associated with a substring of the passed, tokenized string,
	* or null if no such sequence was encountered in the ingested data.
	* @param tokens The tokenized text.
	* @param offset The index of the first token to include in the sequence.
	* @param length The number of tokens to include in the returned sequence.
	*/
	public TokenSequence getTokenSequence(List<Token> tokens, int offset, int length) {
		TokenSequence node = root;
		for (int i = 0; i < tokens.size(); i++) {
			node = node.getChildOrNull(tokens.get(i));
			if (node == null) return null;
		}
		
		return node;
	}
	
	/**
	* Returns the token sequence associated with the passed, tokenized string,
	* or null if no such sequence was encountered in the ing3e3sted data.
	* @param tokens The tokenized text.
	*/
	public TokenSequence getTokenSequence(List<Token> tokens) {
		return getTokenSequence(tokens, 0, tokens.size());
	}
	
	/**
	* Returns the total number of unique tokens encountered.
	* @return The total number of unique tokens encountered.
	*/
	public int getNumTokens() {
		return all_tokens.getNumTokens();
	}
	
	/**
	* Returns the total number of unique token sequences encountered.
	* @return The total number of unique tokens sequences encountered.
	*/
	public int getNumTokenSequences() {
		return num_token_sequences;
	}
	
	/**
	* Returns the bias in the training data ingested so far.
	* @return The mean of the sentiment labels of all ingested lines.
	*/
	public double getAllScoreMean() {
		return root.getScoreMean();
	}
	
	/**
	* Returns the standard deviation of the bias in the training data ingested so far.
	* @return The standard deviation in the sentiment labels of all ingested lines.
	*/
	public double getAllScoreStdDev() {
		return root.getScoreStdDev();
	}
	
	// Ingests the passed number of lines into the passed TokenDictionary, training the model.
	/**
	* Reads labeled data from the passed file and uses it to train the model.
	* Optionally leaves out a portion of the data to be used for testing at a later time.
	* @param file The file to read labeled data from.
	* @param total_num_lines The number of lines to read, although a portion may be skipped over and not actually used for training.
	* @param num_folds The number of folds to divide the training data into. If nonzero, all but one such fold will be used for training. Otherwise, all data will be used for training.
	* @param omit_fold_index The index (ranging from 0 to num_folds-1) of the fold to exclude from training.
	* @throws FileNotFoundException When the passed file does not exist.
	* @throws IllegalArgumentException
	* @throws IOException When an IO error occurs while reading the passed file.
	*/
	public void buildFromFile(File file, int total_num_lines, int num_folds, int omit_fold_index) throws FileNotFoundException, IllegalArgumentException, IOException {
		if (file == null) throw new IllegalArgumentException("file must be non-null.");
		if (total_num_lines <= 0) throw new IllegalArgumentException("total_num_lines must be positive.");
		if (num_folds < 0) throw new IllegalArgumentException("num_folds must be non-negative.");
		if (omit_fold_index < 0) throw new IllegalArgumentException("omit_fold_index must be non-negative.");
		if (num_folds > 0 && omit_fold_index >= num_folds) throw new IllegalArgumentException("omit_fold_index must be less than num_folds.");
		if (num_folds != 0 && total_num_lines % num_folds != 0) throw new IllegalArgumentException("num_folds must evenly divide total_num_lines.");
		
		int total_training_lines = total_num_lines;
		int lines_per_fold = total_num_lines;
		if (num_folds > 0) {
			lines_per_fold /= num_folds;
			total_training_lines -= lines_per_fold;
		}
		
		if (num_folds == 0) {
			// Modify params such that all lines are ingested.
			num_folds = 1;
			omit_fold_index = -1;
			
			System.out.println(String.format(
				"Building full model from %d lines.",
				total_num_lines
			));
		}
		else {
			System.out.println(String.format(
				"Building model from %d folds consisting of %d lines each, Fold %d omitted. Training on %d lines total.",
				num_folds, lines_per_fold, omit_fold_index, total_training_lines
			));
		}
		
		System.out.println("Constructing Dictionary...");
		status_stage = Stage.WORDS;
		
		// Build the token dictionary.
		all_tokens.buildFromFile(file, total_num_lines, num_folds, omit_fold_index, this);
		
		System.out.println(String.format("Dictionary built. %d tokens encountered.", all_tokens.getNumTokens()));
		System.out.println("Pruning Dictionary...");
		
		all_tokens.prune(min_token_occurence);
		
		System.out.println(String.format("Dictionary finalized. %d tokens retained.", all_tokens.getNumTokens()));
		
		System.out.println("Analyzing token sequences...");
		status_stage = Stage.PHRASES;
		setStatusPercent(0);
		
		BufferedReader data_scanner = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
		
		// Build the token sequence trie.
		for (int fold_index = 0; fold_index < num_folds; fold_index++) {
			// Skip over a fold.
			if (fold_index == omit_fold_index) {
				for (int line_index = 0; line_index < lines_per_fold; line_index++) data_scanner.readLine();
				continue;
			}
			
			data_scanner.readLine(); // Throw header away.
			for (int line_index = 0; line_index < lines_per_fold; line_index++) {
				String data = data_scanner.readLine();
				assert data != null : "Cannot ingest lines, EOF reached.";
				
				// Obtain raw text of the line and its score.
				int statement_start = data.indexOf(" ");
				String line = data.substring(statement_start + 1);
				
				int statement_rating = Integer.parseInt(data.substring(0, statement_start));
				double statement_score = statement_rating / 2.0 - 1.5;
				assert statement_score >= -1 && statement_score <= 1 : statement_rating + ", " + statement_score;
				
				// Use the root to track stats on all ingested lines.
				root.addScore(statement_score);
				
				ArrayList<Token> tokens = all_tokens.tokenize(line);
				
				// Add scores to discovered token sequences.
				for (int i = 0; i < tokens.size(); i++) {
					TokenSequence node = root;
					boolean is_new = false;
					
					for (int j = i; j < tokens.size() && j - i < max_token_sequence_length; j++) {
						Token next_token = tokens.get(j);
						if (next_token == null) break;
						
						is_new = is_new || !node.hasChild(next_token);
						node = node.getOrCreateChild(next_token);
						node.addScore(statement_score);
						
						if (is_new) {
							num_token_sequences++;
						}
					}
				}
				
				num_lines_analyzed++;
				if (num_lines_analyzed % 1000 == 0) {
					setStatusPercent((double) num_lines_analyzed / total_training_lines);
					
					if (num_lines_analyzed % 100000 == 0) {
						System.out.println(String.format("%d Lines Analyzed. %d unique token sequences encountered so far.", num_lines_analyzed, num_token_sequences));
					}
				}
				
				if (num_lines_analyzed % sequence_pruning_interval == 0) {
					// The effective min occurence used for pruning is very leniant,
					// in order to lower the chance that sequences will be incorrectly deleted early on as a result of an unusually low rate of occurence in the earlier reviews.
					double min_token_occurence_mul = Math.pow((double) num_lines_analyzed / total_training_lines, 1.4);
					int effective_min_token_occurence = (int) (min_token_occurence * min_token_occurence_mul);
					
					if (effective_min_token_occurence > 1) {
						pruneSequenceTrie(effective_min_token_occurence);
						System.out.println(String.format("Sequence trie pruned with %d min occurences. %d unique token sequences retained.", effective_min_token_occurence, num_token_sequences));
					}
				}
			}
			
			setStatusPercent(1);
		}
		
		pruneSequenceTrie(min_token_occurence);
		System.out.println(String.format("Analysis complete. %d token sequences retained.", num_token_sequences));
		status_stage = Stage.RENORMALIZING;
		
		data_scanner.close();
		
		// Renormalize the outputs.
		data_scanner = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
		
		int num_lines_sampled = 0;
		StatisticsTracker generated_labels = new StatisticsTracker(0, -1, 1);
		for (int fold_index = 0; fold_index < num_folds; fold_index++) {
			// Skip over a fold.
			if (fold_index == omit_fold_index) {
				for (int line_index = 0; line_index < lines_per_fold; line_index++) data_scanner.readLine();
				continue;
			}
			
			for (int line_index = 0; line_index < lines_per_fold; line_index++) {
				String data = data_scanner.readLine();
				assert data != null : "Cannot ingest lines, EOF reached.";
				
				// Obtain raw text of the line
				int statement_start = data.indexOf(" ");
				String line = data.substring(statement_start + 1);
				
				// Generate many labels and get their standard deviation.
				double label = getLabel(line);
				generated_labels.addValue(label);
				
				num_lines_sampled++;
				if (num_lines_sampled == num_output_renormalization_samples) {
					System.out.println(generated_labels.getStdDev());
					gen_labels_mul = getAllScoreStdDev() / generated_labels.getStdDev();
					gen_labels_off = getAllScoreMean() - generated_labels.getMean();
					assert gen_labels_mul > 0 : gen_labels_mul + " is not positive.";
					
					System.out.println(String.format(
						"Output renormalization complete.\nGen. Mean (%.2f) - Act. Mean (%.2f) = %.2f\nGen. Std. Dev. (%.2f) / Act. Std. Dev. (%.2f) = %.2f",
						getAllScoreMean(), generated_labels.getMean(), gen_labels_off, generated_labels.getStdDev(), getAllScoreStdDev(), gen_labels_mul
					));
					break;
				}
			}
			
			if (num_lines_sampled == num_output_renormalization_samples) break;
		}
		
		data_scanner.close();
		status_stage = Stage.COMPLETE;
	}
	
	/**
	* Divides the given set of lines into a number of folds and tests one of them,
	* returning a statistical analysis of the results. Useful in k-fold cross-validation of a model.
	* @param file The file to read labeled data from.
	* @param total_num_lines The number of lines which will be divided into folds, only one of which will be tested.
	* @param num_folds The number of folds to divide the training data into. If zero, all data will be used for testing.
	* @param test_fold_index The index (ranging from 0 to num_folds-1) of the fold to test. Presumably, this is given the same value that was passed as omit_fold_index to buildFromFile().
	* @throws FileNotFoundException When the passed file does not exist.
	* @throws IllegalArgumentException
	* @throws IOException When an IO error occurs while reading the passed file.
	*/
	public ModelTestResults testOnLines(File file, int total_num_lines, int num_folds, int test_fold_index) throws FileNotFoundException, IllegalArgumentException, IOException {
		if (file == null) throw new IllegalArgumentException("file must be non-null.");
		if (total_num_lines <= 0) throw new IllegalArgumentException("total_num_lines must be positive.");
		if (num_folds < 0) throw new IllegalArgumentException("num_folds must be non-negative.");
		if (test_fold_index < 0) throw new IllegalArgumentException("test_fold_index must be non-negative.");
		if (num_folds > 0 && test_fold_index >= num_folds) throw new IllegalArgumentException("test_fold_index must be less than num_folds.");
		if (num_folds != 0 && total_num_lines % num_folds != 0) throw new IllegalArgumentException("num_folds must evenly divide total_num_lines.");
		
		int lines_per_fold = total_num_lines;
		if (num_folds > 0) {
			lines_per_fold /= num_folds;
		}
		
		if (num_folds == 0) {
			// Modify params such that all lines are tested.
			num_folds = 1;
			test_fold_index = 0;
		}
		
		System.out.println(
			String.format("Testing model on fold %d of %d, consisting of %d lines.", test_fold_index, num_folds-1, lines_per_fold)
		);
		
		BufferedReader data_scanner = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
		data_scanner.readLine();
		for (int fold_index = 0; fold_index < num_folds; fold_index++) {
			if (fold_index == test_fold_index) {
				ModelTestResults results = new ModelTestResults(200); // TODO: Magic number.
				for (int line_index = 0; line_index < lines_per_fold; line_index++) {
					String data = data_scanner.readLine();
					if (data == null) break;
					
					// Obtain raw text of the line and its score.
					int statement_start = data.indexOf(" ");
					String line = data.substring(statement_start + 1);
					double result = getLabel(line);
					
					int label = Integer.parseInt(data.substring(0, statement_start));
					double statement_score = label / 2.0 - 1.5;
					assert statement_score >= -1 && statement_score <= 1 : label + ", " + statement_score;
//					System.out.println(result);
					
					results.addResult(result, statement_score);
				}
				
				data_scanner.close();
				
				System.out.println("Testing Complete.");
				System.out.println(results);
				return results;
			}
			else {
				// Skip 
				for (int line_index = 0; line_index < lines_per_fold; line_index++) data_scanner.readLine();
			}
		}
		
		assert false;
		return null;
	}
	
	/**
	* Returns a label for the passed raw text.
	* Begins by converting the string into a list of tokens (tokenization)
	* and taking a weighted average of their associated scores.
	*/
	public double getLabel(String line) {
		line = Model.sanitize(line);
		ArrayList<Token> tokens = all_tokens.tokenize(line);
		
		double total_score = 0;
		double total_weight = 0;
		
		HashSet<TokenSequence> already_counted = new HashSet<TokenSequence>();
		int[] max_sequence_lengths = new int[tokens.size()];
		TokenSequence[] max_sequences = new TokenSequence[tokens.size()];
		
		// The review contains no understood tokens.
		if (tokens.size() == 0) return 0;
		
		for (int i = 0; i < tokens.size(); i++) {
			TokenSequence node = root;
			int curr_sequence_length = 0;
			
			for (int j = i; j < tokens.size(); j++) {
				Token token = tokens.get(j);
				
				TokenSequence next_node = node.getChildOrNull(token);
				if (next_node != null) {
					curr_sequence_length++;
					node = next_node;
				}
				
				if (next_node == null || j == tokens.size() - 1) {
					for (int k = i; k < i + curr_sequence_length; k++) {
						if (max_sequence_lengths[k] < curr_sequence_length) {
							max_sequence_lengths[k] = curr_sequence_length;
							max_sequences[k] = node;
						}
					}
					
					break;
				}
			}
				
			// We now have the maximum-length token sequence belonging to the current node.
			// Add its contribution if it has not already been considered.
			if (max_sequences[i] != null) {
				TokenSequence current_sequence = max_sequences[i];
				for (int j = i; j < tokens.size() && max_sequences[j] == current_sequence; j++) {
					max_sequences[j] = null;
				}
				
				double token_score = getNormalizedMeanScore(current_sequence);
				double weight = 
					Math.pow(Math.abs(token_score), POLARITY_BIAS_EXP) /
					Math.pow(Math.sqrt(Math.pow(current_sequence.getScoreStdDev(), 2) + 0.02), DEVIATION_BIAS_EXP); // Approximates reciprical w/o allowing for division by zero.
//				System.out.println(current_sequence.getString() + ": " + token_score + " (<- " + current_sequence.getScoreMean() + ") * " + weight);
				
				assert token_score >= -1 && token_score <= 1 : token_score + " is not in the range -1.0 - 1.0";
				assert !Double.isNaN(weight);
				assert weight != 0;
				
				double contribution = token_score * weight;
				
				total_score += contribution;
				total_weight += weight;
			}
		}
		
		if (total_weight == 0) {
			return 0;
		}
		else {
			double ret = ((total_score / total_weight + gen_labels_off) - getAllScoreMean()) * gen_labels_mul + getAllScoreMean();
			ret = Math.max(Math.min(ret, 2), -2);
			assert ret >= -2 && ret <= 2 : ret + " is not in the range -2.0 - 2.0";
			return ret;
		}
	}
	
	/**
	* Saves a copy of this model to the passed file.
	* The suggested extension is .ekmd
	* @param file the file to save to.
	* @throws IOException if an error occurs during file IO.
	*/
	public void saveToFile(File file) throws FileNotFoundException, IOException {
		FileOutputStream fout = new FileOutputStream(file);
		
		ByteBuffer header = ByteBuffer.allocate(48);
		for (int i = 0; i < 8; i++) header.put((byte) "EkoModel".charAt(i));
		header.putInt(num_token_sequences);
		header.putInt(num_lines_analyzed);
		
		header.putDouble(gen_labels_mul);
		header.putDouble(gen_labels_off);
		
		header.putInt(max_token_sequence_length);
		header.putInt(min_token_occurence);
		header.putInt(sequence_pruning_interval);
		header.putInt(num_output_renormalization_samples);
		
		fout.write(header.array());
		
		all_tokens.writeToByteStream(fout);
		root.writeToByteStream(fout);
		
		fout.close();
	}
	
	// Returns a TokenSequence's Mean Score adjusted to account for bias in the training data.
	private double getNormalizedMeanScore(TokenSequence tokenSeq) {
		double neutral = getAllScoreMean();
		double score = tokenSeq.getScoreMean();
		
		int sgn = neutral >= 0 ? 1 : -1;
		double exp = 1 / (1 - Math.log(Math.abs(neutral) + 1)/Math.log(2.0));
		double mul = Math.pow(2, 1 - exp);
		
		return sgn * (mul * Math.pow(sgn * score + 1, exp) - 1);
	}
	
	private static String getStageName(Stage stage) {
		switch (stage) {
			case Stage.INIT:
				return "initialization";
			case Stage.WORDS:
				return "words";
			case Stage.PHRASES:
				return "phrases";
			case Stage.RENORMALIZING:
				return "renormalizing";
			case Stage.COMPLETE:
				return "complete";
			default:
				assert false;
				return null;
		}
	}
	
	private void pruneSequenceTrie(int min_num_occurences) {
		root.calculateSequenceCounts(min_num_occurences);
		root.pruneEmptySequences();
		num_token_sequences = root.getSequenceCount();
	}
	
	protected void setStatusPercent(double percent) {
		status_percent = percent;
	}
}