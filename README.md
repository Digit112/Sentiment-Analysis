# Sentiment Analysis

A Java application which includes a web front-end that allows the creation and utilization of sentiment analysis models.

Run with `gradlew run` in the root directory. Note that labeled data is not included.

You can download labeled data and sample unlabeled data (the latter extracted from the former!) at the link below:
https://drive.google.com/drive/folders/1J-erUXpObKWbJ6bhVVYkOOX6xr_Eaqai?usp=sharing

The dataset ('yelp-reviews') must be unzipped into the `app/labeled-data/` folder.

## Labeled Data

Training can be performed on bulk labeled data. Such data has a statement on each line, preceeded by a number from 1 to 5 and a space.
The integers constitute labels for the remainder of each line. The first line will be interpreted as containing the number of labeled statements in the file.

## Bulk Data Labeling

A file can be uploaded via the web portal. Each line will be interpreted as being a whole statement which will be rated.
The UI will update to include a statistical analysis of the generated labels, and a sample of the most polarized statements.

## Security

A login page is present and will prompt the user for login. Although, passwords are not yet stored in a secure fashion.
If an `app/logins` file is not found within, (none is included in the repository) then one will be created on startup with a single
default username/password pair: "admin" and "password".

No permission levels currently exist.