# Sentiment Analysis

A Java application which includes a web front-end that allows the creation and utilization of sentiment analysis models.

## Labeled Data

Training can be performed on bulk labeled data. Such data has a statement on each line, preceeded by a number from 1 to 5 and a space.
The integers constitute labels for the remainder of each line. The first line will be interpreted as containing the number of labeled statements in the file.

## Bulk Data Labeling

A file can be uploaded via the web portal. Each line will be interpreted as being a whole statement which will be rated.
The UI will update to include a statistical analysis of the generated labels, and a sample of the most polarized statements.