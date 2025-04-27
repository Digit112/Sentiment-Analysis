package com.evelynsalie;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;

// The TokenDictionary class stores all indivisible tokens (words) in the training data as a prefix tree.
public class TokenDictionary implements Iterable<Token> {
	// The root of the prefix tree.
	private Token root;
	private int num_tokens;
	
	public TokenDictionary() {
		this.root = new Token('\0', null);
		this.num_tokens = 0;
	}
	
	public TokenDictionary(DataInputStream din) throws IOException {
		this.num_tokens = din.readInt();
		
		this.root = new Token(din, null);
	}
	
	public Iterator<Token> iterator() {
		return new TokenIterator(root);
	}
	
	public int getNumTokens() {
		return num_tokens;
	}
	
	public Token getTokenOrNull(String line) {
		Token node = root;
		for (int i = 0; i < line.length(); i++) {
			node = node.getChildOrNull(line.charAt(i));
			
			if (node == null) return null;
		}
		
		return node;
	}
	
	// Learns all tokens in the passed string.
	// In the future, these can be recognized and belong to a token sequence.
	public void learnTokens(String line) {
		//System.out.println(line + ": ");
		
		Token node = root;
		boolean is_new = false;
		for (int i = 0; i < line.length(); i++) {
			Character c = line.charAt(i);
			
			if (c != ' ') {
				is_new = is_new || !node.hasChild(c);
				node = node.getOrCreateChild(c);
			}
			else {
				if (is_new) {
					num_tokens++;
					//System.out.print(node.getString() + ", ");
				}
				
				if (node != root) node.addOccurence();
				
				is_new = false;
				node = root;
			}
		}
		
		// Counts the last token in a string that does not end with whitespace.
		if (is_new) {
			num_tokens++;
			//System.out.print(node.getString() + ", ");
		}
		
		if (node != root) node.addOccurence();
	}
	
	// Converts a string consisting of words into a list of tokens.
	// Unrecognized sequences of characters are converted to null.
	public ArrayList<Token> tokenize(String line) {
		Token node = root;
		ArrayList<Token> tokens = new ArrayList<Token>();
		for (int i = 0; i < line.length(); i++) {
			Character c = line.charAt(i);
			
			if (c != ' ') {
				node = node.getChildOrNull(c);
				
				// Skip to the next space if no token corresponds to this word.
				if (node == null) {
					while (i < line.length() && line.charAt(i) != ' ') i++;
					node = root;
				}
			}
			else if (node != root) {
				if (node.getNumOccurences() > 0) tokens.add(node);
				node = root;
			}
		}
		
		// Counts the last token in a string that does not end with whitespace.
		if (node != root && node.getNumOccurences() > 0) tokens.add(node);
		
		return tokens;
	}
	
	// Divides total_num_lines lines into folds and learns tokens from all but one of them.
	// Parameters are copied from the caller.
	protected void buildFromFile(File file, int total_num_lines, int num_folds, int omit_fold_index, Model model) throws FileNotFoundException, IOException {
		BufferedReader data_scanner = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
		
		System.out.println(total_num_lines + ", " + num_folds + ", " + omit_fold_index);
		int total_training_lines = total_num_lines;
		int lines_per_fold = total_num_lines;
		if (omit_fold_index != -1) {
			lines_per_fold /= num_folds;
			total_training_lines -= lines_per_fold;
		}
		
		model.setStatusPercent(0);
		
		int num_lines = Integer.parseInt(data_scanner.readLine());
		
		for (int fold_index = 0; fold_index < num_folds; fold_index++) {
			// Skip one fold.
			if (fold_index == omit_fold_index) {
				for (int line_index = 0; line_index < lines_per_fold; line_index++) data_scanner.readLine();
				continue;
			}
			
			for (int line_index = 0; line_index < lines_per_fold; line_index++) {
				String line = data_scanner.readLine();
				if (line == null) break;
				
				// Obtain raw text of the line and its score.
				line = line.substring(line.indexOf(" ") + 1);
				
				// Generate tokens.
				learnTokens(line);
				
				int num_lines_ingested = fold_index*num_folds + line_index + 1;
				if (num_lines_ingested % 1000 == 0) {
					model.setStatusPercent((double) num_lines_ingested / total_training_lines);
					if (num_lines_ingested % 100000 == 0) {
						System.out.println(String.format("%d Lines Learned. %d unique tokens encountered so far.", num_lines_ingested, getNumTokens()));
					}
				}
			}
		}
		
		data_scanner.close();
		model.setStatusPercent(1);
	}
	
	// Removes all tokens with less than the specified minimum number of occurences.
	protected void prune(int min_num_occurences) {
		root.calculateTokenCounts(min_num_occurences);
		root.pruneEmptyTokens();
		num_tokens = root.getTokenCount();
	}
	
	// Assigns all tokens a contiguous index, starting at 1.
	protected void index() {
		int i = root.index(0);
		System.out.println("Done Indexing: " + (i-1) + " Nodes.");
	}
	
	// Returns the token with the given index, as assigned by a prior call to index()
	protected Token getByIndex(int index) {
		return root.getByIndex(index, 0);
	}
	
	protected void writeToByteStream(FileOutputStream fout) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(4);
		header.putInt(num_tokens);
		
		fout.write(header.array());
		index();
		root.writeToByteStream(fout);
	}
}