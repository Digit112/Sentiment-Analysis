package com.evelynsalie;

import java.util.UUID;
import java.time.Instant;

/**
* A token which allows access to a running Sentiment Analysis web server instance.
*/
public class BearerToken {
	private long expires;
	private UUID uuid;
	
	/**
	* Creates a bearer token.
	* @param lifetime The number of seconds until this token expires.
	*/
	public BearerToken(int lifetime) {
		expires = Instant.now().getEpochSecond() + lifetime;
		uuid = UUID.randomUUID();
	}	
	
	public boolean isExpired() {
		return Instant.now().getEpochSecond() >= expires;
	}
	
	public String toString() {
		return uuid.toString();
	}
	
	public boolean equals(BearerToken other) {
		return uuid.equals(other.uuid);
	}
	
	public int hashCode() {
		return uuid.toString().hashCode();
	}
}