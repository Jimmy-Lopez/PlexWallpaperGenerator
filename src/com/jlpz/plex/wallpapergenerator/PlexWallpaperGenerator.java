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
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PlexWallpaperGenerator {
    // TODO see #ConfigurableLayout
    private static final Dimension STILL_DIMENSION = new Dimension(1924, 1080);
    // TODO see #ConfigurableLayout
    private static final int POSTER_HEIGHT = 692;
    private static final String TARGET_DIRECTORY_PATH = PlexWallpaperGenerator.getMandatorySingleSystemProperty(
	    "TARGET_DIRECTORY_PATH", "Full path of the directory where the wallpaper images will be generated");
    private static final String SERVER_URL = PlexWallpaperGenerator.getMandatorySingleSystemProperty("SERVER_URL",
	    "Root URL of the PLEX server (e.g. \"http://192.168.0.1:32400\")");
    private static final String AUTHENTICATION_TOKEN = PlexWallpaperGenerator.getMandatorySingleSystemProperty(
	    "AUTHENTICATION_TOKEN",
	    "X-Plex-Token to use to authenticate on the PLEX server (see https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/)");
    private static final String LIBRARY_ID = PlexWallpaperGenerator.getMandatorySingleSystemProperty("LIBRARY_ID",
	    "Section ID of the library you want to generate wallpapers from (you can find it in the attribute \"librarySectionID\" of the same XML page used to retrieve the X-Plex-Token)");
    private static final String[] FORBIDDEN_KEYWORDS = PlexWallpaperGenerator.getOptionalMultipleSystemProperty(
	    "FORBIDDEN_KEYWORDS",
	    "Case-insensitive keywords that - if at least one is contained in a title - indicate which movies are to be skipped");
    private static final Set<String> MANDATORY_GENRES = Arrays
	    .stream(PlexWallpaperGenerator.getOptionalMultipleSystemProperty("MANDATORY_GENRES",
		    "Case-insensitive genres that - if at least one is contained in tags - indicate which movies are to be processed"))
	    .collect(Collectors.toSet());
    private static final boolean SIMULATED = Boolean
	    .valueOf(PlexWallpaperGenerator.getOptionalSingleSystemProperty("SIMULATED", "\"" + Boolean.TRUE.toString()
		    + "\" to simulate the process without actually generating/deleting any images"));
    private static final String MANDATORY_FILE_PATH_PATTERN = PlexWallpaperGenerator.getOptionalSingleSystemProperty(
	    "MANDATORY_FILE_PATH_PATTERN",
	    "Wildcard pattern (i.e. which may use the characters '?' and '*' to represent respectively a single or multiple (zero or more) unspecified characters) that file path of movies must respect for them to be processed");
    private static final String IMAGE_FORMAT_DEFAULT = "JPG";
    private static final String IMAGE_FORMAT = Optional
	    .ofNullable(PlexWallpaperGenerator.getOptionalSingleSystemProperty("IMAGE_FORMAT",
		    "Format to use to generate images (e.g.: JPG, PNG, etc; default being \"" + IMAGE_FORMAT_DEFAULT
			    + "\")"))
	    .orElse(IMAGE_FORMAT_DEFAULT).toLowerCase();
    private static final String IMAGE_QUALITY_DEFAULT = "75";
    private static final float IMAGE_QUALITY = (float) Integer
	    .valueOf(
		    Optional.ofNullable(
			    PlexWallpaperGenerator
				    .getOptionalSingleSystemProperty("IMAGE_QUALITY",
					    "Percentage of quality to apply when generating images (default being \""
						    + IMAGE_QUALITY_DEFAULT + "\")"))
			    .orElse(IMAGE_QUALITY_DEFAULT))
	    / 100f;

    private static String getMandatorySingleSystemProperty(final String shortName,
	    final String helpDescriptionIfAbsent) {
	final String fullName = PlexWallpaperGenerator.class.getName() + "." + shortName;
	final String value = System.getProperty(fullName);
	if (value == null)
	    System.err.println(
		    "A system property " + fullName + " is required (e.g. by passing a command-line argument \"-D"
			    + fullName + "=<value>\"). Its value should be: " + helpDescriptionIfAbsent);
	return value;
    }

    private static String getOptionalSingleSystemProperty(final String shortName,
	    final String helpDescriptionIfAbsent) {
	// TODO There's some factorization to do between all these methods
	final String fullName = PlexWallpaperGenerator.class.getName() + "." + shortName;
	final String value = System.getProperty(fullName);
	if (value == null || value.strip().length() == 0) {
	    System.err.println("Note that a system property " + fullName
		    + " can be provided (e.g. by passing a command-line argument \"-D" + fullName
		    + "=<value>\"). Its value would be: " + helpDescriptionIfAbsent);
	    return null;
	}
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

    private static void writeImage(final BufferedImage image, final String format, final float quality,
	    final File targetFile) throws IOException {
	if (quality < 0f)
	    ImageIO.write(image, format, targetFile);
	else {
	    // TODO-CHECK Wouldn't it be good for performances to instanciate Image writer &
	    // params only once and for all?
	    final ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
	    final ImageWriteParam params = writer.getDefaultWriteParam();
	    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
	    params.setCompressionQuality(quality);
	    params.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
	    try (final ImageOutputStream stream = ImageIO.createImageOutputStream(targetFile)) {
		writer.setOutput(stream);
		try {
		    writer.write(null, new IIOImage(image, null, null), params);
		} finally {
		    writer.dispose();
		    stream.flush();
		}
	    }
	}
    }

    private static void handleMovie(final String id, final String stillUrl, final String posterUrl,
	    final String targetFileName) throws IOException {
	final File targetFile = new File(TARGET_DIRECTORY_PATH, targetFileName + "." + id + "." + IMAGE_FORMAT);
	if (targetFile.exists())
	    return;
	final File[] previousFiles = new File(TARGET_DIRECTORY_PATH).listFiles(new FilenameFilter() {
	    @Override
	    public boolean accept(final File directory, final String name) {
		return name.endsWith("." + id + "." + IMAGE_FORMAT);
	    }
	});

	if (SIMULATED)
	    System.out.println(
		    "File would be generated (if not in \"simulated\" mode): " + targetFile.getCanonicalPath());
	else {
	    final Image resizedStillImage = PlexWallpaperGenerator.resizeImage(ImageIO.read(new URL(stillUrl)),
		    STILL_DIMENSION.width, STILL_DIMENSION.height);
	    final Image resizedPosterImage = PlexWallpaperGenerator.resizeImage(ImageIO.read(new URL(posterUrl)), -1,
		    POSTER_HEIGHT);

	    // Combining
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

	    PlexWallpaperGenerator.writeImage(combinedImage, IMAGE_FORMAT, IMAGE_QUALITY, targetFile);
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

    private static Stream<Node> getNodeListAsStream(final NodeList nodeList) {
	return nodeList.getLength() == 0 ? Stream.empty()
		: Stream.iterate(nodeList.item(0), node -> node.getNextSibling()).limit(nodeList.getLength());
    }

    public static void main(final String[] args) {
	boolean warnings = false;
	try {
	    if (MANDATORY_FILE_PATH_PATTERN != null)
		System.out.println("Will only process movies which files respect following pattern: ["
			+ MANDATORY_FILE_PATH_PATTERN + "]");
	    if (!MANDATORY_GENRES.isEmpty())
		System.out.println("Will only process movies which are tagged with one of these genres: ["
			+ String.join(", ", MANDATORY_GENRES) + "]");
	    final OkHttpClient client = new OkHttpClient();
	    final String url = PlexWallpaperGenerator.getFullUrl("/library/sections/" + LIBRARY_ID + "/all");
	    System.out.println("Querying URL: " + url);
	    final Request request = new Request.Builder().url(url).get().build();
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
		    if (MANDATORY_FILE_PATH_PATTERN != null) {
			final Set<String> mediaPartFilePathes = PlexWallpaperGenerator
				.getNodeListAsStream(videoNode.getChildNodes())
				.filter(node -> "Media".equals(node.getNodeName()))
				.flatMap(mediaNode -> PlexWallpaperGenerator
					.getNodeListAsStream(mediaNode.getChildNodes())
					.filter(node -> "Part".equals(node.getNodeName())))
				.map(partNode -> partNode.getAttributes().getNamedItem("file").getNodeValue())
				.collect(Collectors.toSet());
			if (mediaPartFilePathes.stream().filter(
				filePath -> FilenameUtils.wildcardMatchOnSystem(filePath, MANDATORY_FILE_PATH_PATTERN))
				.count() == 0) {
			    System.out
				    .println("Skipped because corresponding files don't respect the mandatory pattern: "
					    + title + " [" + String.join(", ", mediaPartFilePathes) + "]");
			    continue VideoLoop;
			}
		    }
		    if (!MANDATORY_GENRES.isEmpty()) {
			final Set<String> genreNames = PlexWallpaperGenerator
				.getNodeListAsStream(videoNode.getChildNodes())
				.filter(node -> "Genre".equals(node.getNodeName())).map(genreNode -> genreNode
					.getAttributes().getNamedItem("tag").getNodeValue().toLowerCase())
				.collect(Collectors.toSet());
			if (Collections.disjoint(genreNames, MANDATORY_GENRES)) {
			    System.out.println("Skipped because isn't tagged with any of the mandatory genres: " + title
				    + " [" + String.join(", ", genreNames) + "]");
			    continue VideoLoop;
			}
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