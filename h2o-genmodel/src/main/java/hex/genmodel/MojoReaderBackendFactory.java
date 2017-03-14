package hex.genmodel;

import com.google.common.io.ByteStreams;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Factory class for vending MojoReaderBackend object that can be used to load MOJOs from different data sources.
 *
 * <p>This class provides convenience methods for loading MOJOs from files, URLs (this includes classpath resources)
 * and also from a generic InputStream source.</p>
 *
 * <p>User needs to make a choice of a Caching Strategy for MOJO sources that are not file based.
 * Available caching strategies:</p>
 * <ul>
 *  <li>MEMORY: (decompressed) content of the MOJO will be cached in memory, this should be suitable for most cases
 *  (for very large models please make sure that your application has enough memory to hold the unpacked MOJO)</li>
 *  <li>DISK: MOJO is cached in a temporary file on disk, recommended for very large models</li>
 * </ul>
 *
 * <p>Example of using MojoReaderBackendFactory to read a MOJO from a classpath resource:</p>
 *
 * <pre>
 * {@code
 *   public class ExampleApp {
 *     public static void main(String[] args) throws Exception {
 *       URL mojoURL = ExampleApp.class.getResource("/com/company/mojo.zip");
 *       MojoReaderBackend reader = MojoReaderBackendFactory.createReaderBackend(mojoURL, CachingStrategy.MEMORY);
 *       MojoModel model = ModelMojoReader.readFrom(reader);
 *       EasyPredictModelWrapper modelWrapper = new EasyPredictModelWrapper(model);
 *       RowData testRow = new RowData();
 *         for (int i = 0; i < args.length; i++)
 *           testRow.put("C"+i, Double.valueOf(args[i]));
 *       RegressionModelPrediction prediction = (RegressionModelPrediction) modelWrapper.predict(testRow);
 *       System.out.println("Prediction: " + prediction.value);
 *     }
 *   }
 * }
 * </pre>
 */
public class MojoReaderBackendFactory {

  public enum CachingStrategy { MEMORY, DISK }

  public static MojoReaderBackend createReaderBackend(String filename) throws IOException {
    return createReaderBackend(new File(filename));
  }

  public static MojoReaderBackend createReaderBackend(File file) throws IOException {
    if (file.isFile())
      return new ZipfileMojoReaderBackend(file.getPath());
    else if (file.isDirectory())
      return new FolderMojoReaderBackend(file.getPath());
    else
      throw new IOException("Invalid file specification: " + file);
  }

  public static MojoReaderBackend createReaderBackend(URL url, CachingStrategy cachingStrategy) throws IOException {
    try (InputStream is = url.openStream()) {
      return createReaderBackend(is, cachingStrategy);
    }
  }

  public static MojoReaderBackend createReaderBackend(InputStream inputStream, CachingStrategy cachingStrategy) throws IOException {
    switch (cachingStrategy) {
      case MEMORY:
        return createInMemoryReaderBackend(inputStream);
      case DISK:
        return createTempFileReaderBackend(inputStream);
    }
    throw new IllegalStateException("Unexpected caching strategy: " + cachingStrategy);
  }

  private static MojoReaderBackend createInMemoryReaderBackend(InputStream inputStream) throws IOException {
    ZipInputStream zis = new ZipInputStream(inputStream);
    HashMap<String, byte[]> content = new HashMap<>();
    ZipEntry entry;
    while ((entry = zis.getNextEntry()) != null) {
      if (entry.getSize() > Integer.MAX_VALUE)
        throw new IOException("File too large: " + entry.getName());
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      ByteStreams.copy(zis, os);
      content.put(entry.getName(), os.toByteArray());
    }
    return new InMemoryMojoReaderBackend(content);
  }

  private static MojoReaderBackend createTempFileReaderBackend(InputStream inputStream) throws IOException {
    Path tmp = Files.createTempFile("h2o-mojo", ".zip");
    Files.copy(inputStream, tmp, StandardCopyOption.REPLACE_EXISTING);
    File tmpFile = tmp.toFile();
    tmpFile.deleteOnExit(); // register delete on exit hook (in case tmp reader doesn't do the job)
    return new TmpMojoReaderBackend(tmpFile);
  }

}