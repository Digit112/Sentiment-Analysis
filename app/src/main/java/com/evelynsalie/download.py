# This script downloads the training data if it does not already exist.

import kagglehub
import json

# Download latest version
path = kagglehub.dataset_download("yelp-dataset/yelp-dataset")

print("Path to dataset files: ", path)

num_lines = 0

with open(path + "/yelp_academic_dataset_review.json", "r") as fin:
	with open("./training_data.txt", "w") as fout:
		data = fin.readline()
		while data != "":
			num_lines += 1
			
			data = json.loads(data)
			text = data["text"]
			
			new_text = ""
			for c in text:
				c = c.lower()
				
				if (c.isalnum() or c in ['-', '\'', '_']) and c.isascii() and c.isprintable():
					new_text += c
				
				elif len(new_text) > 0 and new_text[-1] != ' ':
					new_text += ' '
			
			if num_lines % 10000 == 0:
				print("%d lines read..." % num_lines)
				
			fout.write("%d %s\n" % (data["stars"], new_text))
			data = fin.readline()

print("%d lines read." % num_lines)