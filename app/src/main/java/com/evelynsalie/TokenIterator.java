package com.evelynsalie;

import java.util.NoSuchElementException;
import java.util.Iterator;
import java.util.Stack;
import java.util.Map;

public class TokenIterator implements Iterator<Token> {
	// A stack of iterators over the children of every ancestor of the previously returned token,
	// including the previously returned token if it has children.
	private Stack<Iterator<Map.Entry<Character, Token>>> token_iterator_stack;
	
	// It is not possible to determine whether another token exists without attempting to get one.
	// If hasNext() is called, the result is cached here on success and returned by the following next() call.
	private Token cached_next;
	
	// True if hasNext() has been called more recently than next().
	private boolean already_tried_getting_next;
	
	protected TokenIterator(Token root) {
		token_iterator_stack = new Stack<Iterator<Map.Entry<Character, Token>>>();
		token_iterator_stack.push(root.getChildren().entrySet().iterator());
		
		cached_next = null;
		already_tried_getting_next = false;
	}
	
	public boolean hasNext() {
		if (!already_tried_getting_next) {
			cached_next = tryNext();
			already_tried_getting_next = true;
		}
		
		return cached_next != null;
	}
	
	/**
	* Obtains the next Token if it exists.
	* Performs preorder traversal.
	*/
	public Token next() {
		if (hasNext()) {
			Token ret = cached_next;
			cached_next = null;
			already_tried_getting_next = false;
			return ret;
		}
		else {
			throw new NoSuchElementException();
		}
	}
	
	private Token tryNext() {
		Token next = null;
		
		while (true) {
			if (token_iterator_stack.empty()) return null;
			
			// Get the last iterator.
			Iterator<Map.Entry<Character, Token>> current_token_iterator = token_iterator_stack.peek();
			
			// If we don't have a node, get one.
			if (next == null) {
				// If we do not have a node or we have an invalid node with no children,
				// get the next node.
				if (current_token_iterator.hasNext()) {
					next = current_token_iterator.next().getValue();
				}
				// If there is no next node, destroy this iterator.
				// A new node will be obtained from the preceeding iterator if it exists.
				else {
//					System.out.print("Parent -> ");
					token_iterator_stack.pop();
					continue;
				}
			}
			
			// If we have a node with children, create an iterator over them.
			if (next.getChildren().size() >= 0) {
				Iterator<Map.Entry<Character, Token>> new_iterator = next.getChildren().entrySet().iterator();
				
				token_iterator_stack.push(new_iterator);
//				System.out.print("Child -> ");
				
				// Return this node if it is valid
				if (next.isRealWord()) {
//					System.out.println("Got " + next.getString());
					return next;
				}
				
				next = new_iterator.next().getValue();
				continue;
			}
			
			if (next.isRealWord()) {
//				System.out.println("Got " + next.getString());
				return next;
			}
		}
	}
}