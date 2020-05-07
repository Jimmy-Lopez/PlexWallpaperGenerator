package com.jlpz.plex.wallpapergenerator;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// TODO Use a dependency manager (e.g. Maven) instead of duplicated JARs
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PlexWallpaperGenerator {
    // TODO see #ConfigurableLayout
    private static final Dimension STILL_DIMENSION = new Dimension(1924, 1080);
    // TODO see #ConfigurableLayout
    private static final int POSTER_HEIGHT = 692;
    private static final String TARGET_DIRECTORY_PATH = PlexWallpaperGenerator.getMandatorySystemProperty(
	    "TARGET_DIRECTORY_PATH", "Full path of the directory where the wallpaper images will be generated");
    private static final String SERVER_URL = PlexWallpaperGenerator.getMandatorySystemProperty("SERVER_URL",
	    "Root URL of the PLEX server (e.g. \"http://192.168.0.1:32400\")");
    private static final String AUTHENTICATION_TOKEN = PlexWallpaperGenerator.getMandatorySystemProperty(
	    "AUTHENTICATION_TOKEN",
	    "X-Plex-Token to use to authenticate on the PLEX server (see https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/)");
    private static final String LIBRARY_ID = PlexWallpaperGenerator.getMandatorySystemProperty("LIBRARY_ID",
	    "Section ID of the library you want to generate wallpapers from (you can find it in the attribute \"librarySectionID\" of the same XML page used to retrieve the X-Plex-Token)");
    private static final String[] FORBIDDEN_KEYWORDS = PlexWallpaperGenerator.getOptionalMultipleSystemProperty(
	    "FORBIDDEN_KEYWORDS",
	    "Case-insensitive keywords that - if at least one is contained in a title - indicate which movies are to be skipped");
    private static final String[] MANDATORY_GENRES = PlexWallpaperGenerator.getOptionalMultipleSystemProperty(
	    "MANDATORY_GENRES",
	    "Case-insensitive genres that - if at least one is contained in tags - indicate which movies are to be processed");

    private static String getMandatorySystemProperty(final String shortName, final String helpDescriptionIfAbsent) {
	final String fullName = PlexWallpaperGenerator.class.getName() + "." + shortName;
	final String value = System.getProperty(fullName);
	if (value == null)
	    System.err.println(
		    "A system property " + fullName + " is required (e.g. by passing a command-line argument \"-D"
			    + fullName + "=<value>\"). Its value should be: " + helpDescriptionIfAbsent);
	return value;
    }

    private static String[] getOptionalMultipleSystemProperty(final String shortName,
	    final String helpDescriptionIfAbsent) {
	final String fullName = PlexWallpaperGenerator.class.getName() + "." + shortName;
	final String value = System.getProperty(fullName);
	if (value == null || value.strip().length() == 0) {
	    System.out.println("Note that a system property " + fullName
		    + " can be provided (e.g. by passing a command-line argument \"-D" + fullName
		    + "=<value1>;...;<valueN>\"). Its values would be: " + helpDescriptionIfAbsent);
	    return new String[0];
	}
	return value.toLowerCase().split(";");
    }

    private static String getFullUrl(final String uri) {
	final String url = SERVER_URL + uri + "?X-Plex-Token=" + AUTHENTICATION_TOKEN;
	return url;
    }

    private static Image resizeImage(final BufferedImage image, final int width, final int height) {
	if (image == null)
	    throw new NullPointerException("No image to resize.");

	if (width == -1 && height == -1 || width == image.getWidth() && height == image.getHeight())
	    return image;

	if (width == -1)
	    return image.getScaledInstance(image.getWidth() * height / image.getHeight(), height, Image.SCALE_SMOOTH);

	if (height == -1)
	    return image.getScaledInstance(width, image.getHeight() * width / image.getWidth(), Image.SCALE_SMOOTH);

	final Point cropOrigin = new Point(0, 0);
	Dimension cropDimension = new Dimension(image.getWidth(), height * image.getWidth() / width);
	if (cropDimension.height < image.getHeight())
	    cropOrigin.y = (image.getHeight() - cropDimension.height) / 2;
	else {
	    cropDimension = new Dimension(width * image.getHeight() / height, image.getHeight());
	    cropOrigin.x = (image.getWidth() - cropDimension.width) / 2;
	}
	return image.getSubimage(cropOrigin.x, cropOrigin.y, cropDimension.width, cropDimension.height)
		.getScaledInstance(width, height, Image.SCALE_SMOOTH);
    }

    private static void handleMovie(final String id, final String stillUrl, final String posterUrl,
	    final String targetFileName) throws IOException {
	final File targetFile = new File(TARGET_DIRECTORY_PATH, targetFileName + "." + id + ".jpg");
	if (targetFile.exists())
	    return;
	final File[] previousFiles = new File(TARGET_DIRECTORY_PATH).listFiles(new FilenameFilter() {
	    @Override
	    public boolean accept(final File directory, final String name) {
		return name.endsWith("." + id + ".jpg");
	    }
	});

	final Image resizedStillImage = PlexWallpaperGenerator.resizeImage(ImageIO.read(new URL(stillUrl)),
		STILL_DIMENSION.width, STILL_DIMENSION.height);
	final Image resizedPosterImage = PlexWallpaperGenerator.resizeImage(ImageIO.read(new URL(posterUrl)), -1,
		POSTER_HEIGHT);

	// Combining
	{
	    // TODO Make the layout of the combined image configurable #ConfigurableLayout
	    final BufferedImage combinedImage = new BufferedImage(
		    resizedStillImage.getWidth(null) + 2 * resizedPosterImage.getWidth(null),
		    Math.max(resizedPosterImage.getHeight(null), resizedStillImage.getHeight(null)),
		    BufferedImage.TYPE_INT_RGB);
	    final Graphics graphics = combinedImage.getGraphics();
	    graphics.drawImage(resizedPosterImage, 0, 0, null);
	    graphics.drawImage(resizedStillImage, resizedPosterImage.getWidth(null), 0, null);
	    graphics.drawImage(resizedPosterImage, resizedPosterImage.getWidth(null) + resizedStillImage.getWidth(null),
		    0, null);

	    ImageIO.write(combinedImage, "JPG", targetFile);
	    System.out.println("File generated: " + targetFile.getCanonicalPath());

	    if (previousFiles.length > 0)
		for (final File previousFile : previousFiles)
		    previousFile.delete();
	}
    }

    private static String sanitizeTitle(final String title) {
	String sanitized = title.toLowerCase().replaceAll("[^\\p{javaLowerCase}\\d]", "_");
	while (sanitized.indexOf("__") != -1)
	    sanitized = sanitized.replace("__", "_");
	if (sanitized.startsWith("_"))
	    sanitized = sanitized.substring(1);
	if (sanitized.endsWith("_"))
	    sanitized = sanitized.substring(0, sanitized.length() - 1);
	return sanitized;
    }

    public static void main(final String[] args) {
	boolean warnings = false;
	try {
	    if (MANDATORY_GENRES.length > 0)
		System.out.println("Will only process movies which are tagged with one of these genres: ["
			+ String.join(", ", MANDATORY_GENRES) + "]");
	    final OkHttpClient client = new OkHttpClient();
	    final Request request = new Request.Builder()
		    .url(PlexWallpaperGenerator.getFullUrl("/library/sections/" + LIBRARY_ID + "/all")).get().build();
	    try (Response response = client.newCall(request).execute()) {
		if (!response.isSuccessful())
		    throw new IOException("Unexpected response code: " + response + " " + response.body().string());

		final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(response.body().byteStream());
		final NodeList videos = document.getElementsByTagName("Video");
		VideoLoop: for (int videoIndex = 0; videoIndex < videos.getLength(); videoIndex++) {
		    final Node videoNode = videos.item(videoIndex);
		    final NamedNodeMap videoAttributes = videoNode.getAttributes();
		    final String id = videoAttributes.getNamedItem("ratingKey").getNodeValue();
		    String title = "";
		    Node titleNode = videoAttributes.getNamedItem("originalTitle");
		    if (titleNode != null)
			title = PlexWallpaperGenerator.sanitizeTitle(titleNode.getNodeValue());
		    if (title.length() == 0) {
			titleNode = videoAttributes.getNamedItem("title");
			title = PlexWallpaperGenerator.sanitizeTitle(titleNode.getNodeValue());
		    }
		    if (videoAttributes.getNamedItem("year") != null)
			title += " (" + videoAttributes.getNamedItem("year").getNodeValue() + ")";
		    final String lowerCaseTitle = title.toLowerCase();
		    for (final String forbiddenKeyword : FORBIDDEN_KEYWORDS)
			if (lowerCaseTitle.contains(forbiddenKeyword)) {
			    System.out.println("Skipped because contains forbidden keyword ["
				    + forbiddenKeyword.toUpperCase() + "]: " + title);
			    continue VideoLoop;
			}
		    GenreCheck: if (MANDATORY_GENRES.length > 0) {
			final NodeList videoChildrenNodes = videoNode.getChildNodes();
			final List<String> genreNames = new ArrayList<>(videoChildrenNodes.getLength());
			for (int videoChildIndex = 0; videoChildIndex < videoChildrenNodes
				.getLength(); videoChildIndex++) {
			    final Node videoChild = videoChildrenNodes.item(videoChildIndex);
			    if (videoChild.getNodeName().equals("Genre")) {
				final NamedNodeMap genreAttributes = videoChild.getAttributes();
				final Node genreTag = genreAttributes.getNamedItem("tag");
				if (genreTag != null) {
				    final String genreName = genreTag.getNodeValue().toLowerCase();
				    for (final String mandatoryGenre : MANDATORY_GENRES)
					if (mandatoryGenre.equals(genreName))
					    break GenreCheck;
				    genreNames.add(genreName);
				}
			    }
			}
			System.out.println("Skipped because isn't tagged with any of the mandatory genres: " + title
				+ " [" + String.join(", ", genreNames) + "]");
			continue VideoLoop;
		    }
		    if (videoAttributes.getNamedItem("art") == null || videoAttributes.getNamedItem("thumb") == null) {
			System.err.println("Image missing for: " + title);
			warnings = true;
			continue;
		    }
		    try {
			PlexWallpaperGenerator.handleMovie(id,
				PlexWallpaperGenerator.getFullUrl(videoAttributes.getNamedItem("art").getNodeValue()),
				PlexWallpaperGenerator.getFullUrl(videoAttributes.getNamedItem("thumb").getNodeValue()),
				title);
		    } catch (final IOException | NullPointerException exception) {
			System.err.println("Error while trying to handle: " + title);
			exception.printStackTrace();
			warnings = true;
			continue;
		    }
		    // TODO Use proper LOG library (e.g. SLF4J); remark to apply to all uses of
		    // System.xxx.print
		    System.out.println(videoIndex + 1 + ". " + title);
		}
	    }

	} catch (final Exception exception) {
	    // TODO Handle exceptions properly
	    exception.printStackTrace();
	    System.exit(1);
	}
	if (warnings)
	    // TODO Document exit codes
	    System.exit(2);
    }
}