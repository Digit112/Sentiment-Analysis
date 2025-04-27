package com.evelynsalie;

import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class TokenSequence {
	private TokenSequence parent;
	private HashMap<Token, TokenSequence> children;
	
	private final Token my_key;
	
	// The maximum depth of the tree rooted at this node.
	private final int max_sequence_length;
	
	// Number of times this token has been added to the dictionary.
	private int occurences;
	
	// Number of valid sequences (terminal nodes with a sufficient number of occurences)
	// descending from this node, including this node itself.
	private int sequence_count;
	
	// Sum of the scores of all statements that this token appears in
	// (token will be counted multiple times for statements in which it appears multiple times!)
	private double score_sum;
	
	// Sum of the squares of the scores of all statements that this token appears in.
	// Used for calculating the standard deviation of this data.
	private double score_sqr_sum;
	
	public TokenSequence(int max_sequence_length, Token my_key, TokenSequence parent) {
		this.my_key = my_key;
		this.parent = parent;
		this.children = new HashMap<Token, TokenSequence>();
		
		assert max_sequence_length >= 0 : "Cannot have a TokenSequence of negative length.";
		this.max_sequence_length = max_sequence_length;
		
		this.sequence_count = 0;
		
		this.occurences = 0;
		this.score_sum = 0;
		this.score_sqr_sum = 0;
	}
	
	public TokenSequence(DataInputStream din, TokenSequence parent, TokenDictionary token_dict) throws IOException {
		this.parent = parent;
		int my_key_index = din.readInt();
		
		if (my_key_index == -1) {
			this.my_key = null;
		}
		else {
			this.my_key = token_dict.getByIndex(my_key_index);
		}
		
		this.max_sequence_length = din.readInt();
		this.occurences = din.readInt();
		this.sequence_count = din.readInt();
		this.score_sum = din.readDouble();
		this.score_sqr_sum = din.readDouble();
		
		int num_children = din.readInt();
		this.children = new HashMap<Token, TokenSequence>();
		for (int i = 0; i < num_children; i++) {
			TokenSequence new_child = new TokenSequence(din, this, token_dict);
			this.children.put(new_child.my_key, new_child);
		}
	}
	
	// Gets the child of this node, which represents a character sequence of one additional character.
	// If the child does not exist, create it.
	public TokenSequence getOrCreateChild(Token key) {
		if (!children.containsKey(key)) {
			children.put(key, new TokenSequence(max_sequence_length-1, key, this));
		}
		
		return children.get(key);
	}
	
	// Gets the child of this node or null if it does not yet exist.
	public TokenSequence getChildOrNull(Token key) {
		return children.get(key);
	}
	
	public boolean hasChild(Token key) {
		return children.containsKey(key);
	}
	
	public int getNumOccurences() {
		return occurences;
	}
	
	public double getCumulativeScore() {
		return score_sum;
	}
	
	// Called with the score of a sentence whenever the token represented by this node is encountered.
	public void addScore(double new_score) {
		occurences++;
		score_sum += new_score;
		score_sqr_sum += new_score*new_score;
	}
	
	// Returns the mean of all values passed to addScore()
	public double getScoreMean() {
		return score_sum / occurences;
	}
	
	// Returns the standard deviation of all values passed to addScore()
	public double getScoreStdDev() {
		return Math.sqrt(score_sqr_sum / occurences - getScoreMean()*getScoreMean());
	}
	
	public boolean isNew() {
		return occurences == 0;
	}
	
	public String getString() {
		if (parent == null) return ""; // Called on root
		return parent.getString() + ' ' + my_key.getString();
	}
	
	protected Map<Token, TokenSequence> getChildren() {
		return children;
	}
	
	protected void calculateSequenceCounts(int min_num_occurences) {
		sequence_count = 0;
		for (Map.Entry<Token, TokenSequence> pair : children.entrySet()) {
			TokenSequence child = pair.getValue();
			
			child.calculateSequenceCounts(min_num_occurences);
			sequence_count += child.sequence_count;
		}
		
		if (occurences >= min_num_occurences) {
			sequence_count++;
		}
		else {
			// Allows occurences to be used later to determine
			// whether a given node is terminal.
			occurences = 0;
		}
	}
	
	protected void pruneEmptySequences() {
		HashMap<Token, TokenSequence> new_children = new HashMap<Token, TokenSequence>();
		for (Map.Entry<Token, TokenSequence> pair : children.entrySet()) {
			Token child_key = pair.getKey();
			TokenSequence child = pair.getValue();
			
			if (child.sequence_count > 0) {
				child.pruneEmptySequences();
				new_children.put(child_key, child);
			}
		}
		
		children = new_children;
	}
	
	protected int getSequenceCount() {
		return sequence_count;
	}
	
	protected void writeToByteStream(FileOutputStream fout) throws IOException {
		ByteBuffer object = ByteBuffer.allocate(36);
		
		if (my_key == null) {
			object.putInt(-1);
		}
		else {
			object.putInt(my_key.index);
		}
		
		object.putInt(max_sequence_length);
		object.putInt(occurences);
		object.putInt(sequence_count);
		object.putDouble(score_sum);
		object.putDouble(score_sqr_sum);
		object.putInt(children.size());
		
		fout.write(object.array());
		
		for (Map.Entry<Token, TokenSequence> pair : children.entrySet()) {
			pair.getValue().writeToByteStream(fout);
		}
	}
}