package com.evelynsalie;

import java.nio.ByteBuffer;
import java.io.DataInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Token {
	private HashMap<Character, Token> children;
	private Token parent;
	
	private final Character my_key;
	
	// Number of times this token was encountered in the training data.
	private int num_occurences;
	
	private int num_descendants;
	
	// Number of tokens which were not pruned, which descend from this node,
	// including this node if it was encountered in the training data.
	private int token_count;
	
	// Used while saving a model to convert token references into token indices.
	protected int index;
	
	public Token(Character my_key, Token parent) {
		this.my_key = my_key;
		this.parent = parent;
		this.children = new HashMap<Character, Token>();
		
		this.num_occurences = 0;
		this.num_descendants = 0;
		this.token_count = 0;
		this.index = -1;
	}
	
	// Reads a token (and all its descendants) from a file.
	protected Token(DataInputStream din, Token parent) throws IOException {
		this.parent = parent;
		
		this.my_key = din.readChar();
		this.index = din.readInt();
		this.num_occurences = din.readInt();
		this.num_descendants = din.readInt();
		this.token_count = din.readInt();
		
		int num_children = din.readInt();
		this.children = new HashMap<Character, Token>();
		for (int i = 0; i < num_children; i++) {
			Token new_token = new Token(din, this);
			this.children.put(new_token.my_key, new_token);
		}
	}
	
	// Gets the child of this node, which represents a character sequence of one additional character.
	// If the child does not exist, create it.
	public Token getOrCreateChild(Character key) {
		if (!children.containsKey(key)) {
			children.put(key, new Token(key, this));
		}
		
		return children.get(key);
	}
	
	public Token getChildOrNull(Character key) {
		return children.get(key);
	}
	
	public boolean hasChild(Character key) {
		return children.containsKey(key);
	}
	
	public boolean isRealWord() {
		return num_occurences > 0;
	}
	
	// Calculates the number of full tokens which were encountered in the text more
	// than min_num_occurences times.
	public void calculateTokenCounts(int min_num_occurences) {
		token_count = 0;
		for (Map.Entry<Character, Token> pair : children.entrySet()) {
			Token child = pair.getValue();
			
			child.calculateTokenCounts(min_num_occurences);
			token_count += child.token_count;
		}
		
		if (num_occurences >= min_num_occurences) {
			token_count++;
		}
		else {
			// Allows num_occurences to be used later to determine
			// whether a given node is terminal.
			num_occurences = 0;
		}
	}
	
	public void pruneEmptyTokens() {
		HashMap<Character, Token> new_children = new HashMap<Character, Token>();
		for (Map.Entry<Character, Token> pair : children.entrySet()) {
			Character child_key = pair.getKey();
			Token child = pair.getValue();
			
			if (child.token_count > 0) {
				child.pruneEmptyTokens();
				new_children.put(child_key, child);
			}
		}
		
		children = new_children;
	}
	
	public String getString() {
		if (parent == null) return ""; // Called on root
		return parent.getString() + my_key.toString();
	}
	
	// Assigns an integer greater than the passed integer to this token and all children.
	// Pre-order traversal ensures all children receive greater indices than their parents.
	// Returns the last assigned index.
	protected int index(int next_index) {
		index = next_index;
		next_index++;
		
		for (Map.Entry<Character, Token> pair : children.entrySet()) {
			next_index = pair.getValue().index(next_index);
		}
		
		num_descendants = next_index - index - 1;
		return next_index;
	}
	
	// Returns the token with the given index, as assigned by a prior call to index()
	protected Token getByIndex(int desired_index, int depth) {
		String prefix = "";
		for (int i = 0; i < depth; i++) prefix += "  ";
		
		if (index == desired_index) {
//			System.out.println("Returning.");
			return this;
		}
		else {
			for (Map.Entry<Character, Token> pair : children.entrySet()) {
				Token child = pair.getValue();
				int min_index = child.index;
				int max_index = child.index + child.num_descendants;
				
//				System.out.println(prefix + "'" + pair.getKey() + "': " + min_index + " <= " + desired_index + " <= " + max_index);
				if (min_index <= desired_index && max_index >= desired_index) {
//					System.out.println(prefix + "Traversing via '" + pair.getKey() + "'");
					Token ret = child.getByIndex(desired_index, depth + 1);
					
					assert ret != null;
					assert ret.index == desired_index;
					
					return ret;
				}
			}
			
			assert false;
			return null;
		}
	}
	
	protected Map<Character, Token> getChildren() {
		return children;
	}
	
	protected int getTokenCount() {
		return token_count;
	}
	
	protected int getNumOccurences() {
		return num_occurences;
	}
	
	protected void addOccurence() {
		num_occurences++;
	}
	
	protected void writeToByteStream(FileOutputStream fout) throws IOException {
		ByteBuffer object = ByteBuffer.allocate(22);
		
		object.putChar(my_key);
		object.putInt(index);
		object.putInt(num_occurences);
		object.putInt(num_descendants);
		object.putInt(token_count);
		object.putInt(children.size());
		
		fout.write(object.array());
		
		for (Map.Entry<Character, Token> pair : children.entrySet()) {
			pair.getValue().writeToByteStream(fout);
		}
	}
}