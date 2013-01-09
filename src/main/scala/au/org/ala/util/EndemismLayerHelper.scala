package au.org.ala.util

import collection.mutable.ListBuffer
import java.text.MessageFormat
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod
import au.org.ala.biocache.Config
import org.apache.commons.codec.net.URLCodec
import org.apache.commons.math3.util.Precision
import org.apache.commons.io.IOUtils
import java.io.{BufferedWriter, FileWriter}
import org.apache.commons.lang3.StringUtils
import org.apache.solr.client.solrj.util.ClientUtils

/**
 * Created with IntelliJ IDEA.
 * User: ChrisF
 * Date: 29/11/12
 * Time: 4:13 PM
 * To change this template use File | Settings | File Templates.
 */
object EndemismLayerHelper {
  val FACET_DOWNLOAD_URL_TEMPLATE = Config.biocacheServiceUrl + "/occurrences/facets/download?q={0}&facets={1}"
  //val FACET_DOWNLOAD_URL_TEMPLATE = "http://ala-rufus.it.csiro.au/biocache-service/occurrences/facets/download?q={0}&facets={1}"

  val ALL_SPECIES_QUERY = "species_guid:[* TO *] AND geospatial_kosher:true"
  val SPECIES_QUERY_TEMPLATE = "species_guid:{0} AND geospatial_kosher:true"

  val SPECIES_FACET = "species_guid"
  val POINT_001_FACET = "point-0.001"

  def main(args: Array[String]) {
    val helper = new EndemismLayerHelper();
    //var allSpecies = false;
    var outputFileDirectory: String = null;
    var speciesCellCountsFilePrefix: String = null;
    var cellSpeciesFilePrefix: String = null;

    val parser = new OptionParser("Find expert distribution outliers") {
      arg("outputFileDirectory", "Directory in which to write the output files", {
        v: String => outputFileDirectory = v
      })
      arg("speciesCellCountsFilePrefix", "Prefix for files containing species cell counts (without .txt extension)", {
        v: String => speciesCellCountsFilePrefix = v
      })
      arg("cellSpeciesFilePrefix", "Prefix for files containing cell species lists (without .txt extension)", {
        v: String => cellSpeciesFilePrefix = v
      })
      //      booleanOpt("a", "allSpecies", "If true, endemism values will be calcuated for all species, instead of those that were recently updated.", {
      //        v: Boolean => allSpecies = v
      //      })
    }

    if (parser.parse(args)) {
      println("Output file directory: " + outputFileDirectory)
      println("Species cell counts file prefix: " + speciesCellCountsFilePrefix)
      println("Cell species file prefix: " + cellSpeciesFilePrefix)
      helper.calculateSpeciesEndemismValues(outputFileDirectory, speciesCellCountsFilePrefix, cellSpeciesFilePrefix)
    }
  }
}

class EndemismLayerHelper {

  val indexDAO = Config.indexDAO

  def calculateSpeciesEndemismValues(outputFileDirectory: String, speciesCellCountsFilePrefix: String, cellSpeciesFilePrefix: String) {
    // Data for 0.01 degree by 0.01 degree cells
    var cellSpeciesPoint01Degree = scala.collection.mutable.Map[String, Set[String]]()
    var speciesCellCountsPoint01Degree = scala.collection.mutable.Map[String, Int]()

    // Data for 0.1 degree by 0.1 degree cells
    var cellSpeciesPoint1Degree = scala.collection.mutable.Map[String, Set[String]]()
    var speciesCellCountsPoint1Degree = scala.collection.mutable.Map[String, Int]()

    // Data for 1 degree by 1 degree cells
    var cellSpecies1Degree = scala.collection.mutable.Map[String, Set[String]]()
    var speciesCellCounts1Degree = scala.collection.mutable.Map[String, Int]()

    // get list of species
    val speciesLsids = doFacetQuery(EndemismLayerHelper.ALL_SPECIES_QUERY, EndemismLayerHelper.SPECIES_FACET)

    for (lsid <- speciesLsids) {
      println(lsid)
      val occurrencePoints = doFacetQuery(MessageFormat.format(EndemismLayerHelper.SPECIES_QUERY_TEMPLATE, ClientUtils.escapeQueryChars(lsid)), EndemismLayerHelper.POINT_001_FACET)

      // process for 0.01 degree resolution
      processOccurrencePoints(occurrencePoints, lsid, cellSpeciesPoint01Degree, speciesCellCountsPoint01Degree, 2)

      // process for 0.1 degree resolution
      processOccurrencePoints(occurrencePoints, lsid, cellSpeciesPoint1Degree, speciesCellCountsPoint1Degree, 1)

      // process for 1 degree resolution
      processOccurrencePoints(occurrencePoints, lsid, cellSpecies1Degree, speciesCellCounts1Degree, 0)
    }

    //write output for 0.01 degree resolution
    val cellSpeciesFilePoint01Degree = outputFileDirectory + '/' + cellSpeciesFilePrefix + "-0.01-degree.txt"
    val speciesCellCountsFilePoint01Degree = outputFileDirectory + '/' + speciesCellCountsFilePrefix + "-0.01-degree.txt"
    writeFileOutput(cellSpeciesPoint01Degree, speciesCellCountsPoint01Degree, cellSpeciesFilePoint01Degree, speciesCellCountsFilePoint01Degree)

    //write output for 0.1 degree resolution
    val cellSpeciesFilePoint1Degree = outputFileDirectory + '/' + cellSpeciesFilePrefix + "-0.1-degree.txt"
    val speciesCellCountsFilePoint1Degree = outputFileDirectory + '/' + speciesCellCountsFilePrefix + "-0.1-degree.txt"
    writeFileOutput(cellSpeciesPoint1Degree, speciesCellCountsPoint1Degree, cellSpeciesFilePoint1Degree, speciesCellCountsFilePoint1Degree)

    //write output for 1 degree resolution
    val cellSpeciesFile1Degree = outputFileDirectory + '/' + cellSpeciesFilePrefix + "-1-degree.txt"
    val speciesCellCountsFile1Degree = outputFileDirectory + '/' + speciesCellCountsFilePrefix + "-1-degree.txt"
    writeFileOutput(cellSpecies1Degree, speciesCellCounts1Degree, cellSpeciesFile1Degree, speciesCellCountsFile1Degree)
  }

  def processOccurrencePoints(occurrencePoints: ListBuffer[String], lsid: String, cellSpecies: scala.collection.mutable.Map[String, Set[String]], speciesCellCounts: scala.collection.mutable.Map[String, Int], numDecimalPlacesToRoundTo: Int) {
    var pointsSet = Set[String]()

    for (point <- occurrencePoints) {
      val splitPoint = point.split(",")
      val strLatitude = splitPoint(0)
      val strLongitude = splitPoint(1)

      val roundedLatitude = Precision.round(java.lang.Double.parseDouble(strLatitude), numDecimalPlacesToRoundTo, java.math.BigDecimal.ROUND_CEILING)
      val roundedLongitude = Precision.round(java.lang.Double.parseDouble(strLongitude), numDecimalPlacesToRoundTo, java.math.BigDecimal.ROUND_FLOOR)

      val strRoundedCoords = roundedLatitude + "," + roundedLongitude
      pointsSet += strRoundedCoords

      var thisCellSpecies = cellSpecies.getOrElse(strRoundedCoords, null)
      if (thisCellSpecies == null) {
        thisCellSpecies = Set[String]()
      }

      thisCellSpecies += lsid
      cellSpecies.put(strRoundedCoords, thisCellSpecies)
    }

    speciesCellCounts.put(lsid, pointsSet.size)
  }

  def writeFileOutput(cellSpecies: scala.collection.mutable.Map[String, Set[String]], speciesCellCounts: scala.collection.mutable.Map[String, Int], cellSpeciesFile: String, speciesCellCountsFile: String) {
    val bwSpeciesCellCounts = new BufferedWriter(new FileWriter(speciesCellCountsFile));
    for (lsid <- speciesCellCounts.keys) {
      bwSpeciesCellCounts.append(lsid)
      bwSpeciesCellCounts.append(",")
      bwSpeciesCellCounts.append(speciesCellCounts(lsid).toString)
      bwSpeciesCellCounts.newLine()
    }
    bwSpeciesCellCounts.flush()
    bwSpeciesCellCounts.close()

    val bwCellSpecies = new BufferedWriter(new FileWriter(cellSpeciesFile));
    for (cellCoords <- cellSpecies.keys) {
      bwCellSpecies.append(cellCoords)
      bwCellSpecies.append(",")
      bwCellSpecies.append(cellSpecies(cellCoords).mkString(","))
      bwCellSpecies.newLine()
    }
    bwCellSpecies.flush()
    bwCellSpecies.close()
  }

  def doFacetQuery(query: String, facet: String): ListBuffer[String] = {

    var resultsList = new ListBuffer[String]

    def addToList(name: String, count: Int): Boolean = {
      resultsList += name

      true
    }

    indexDAO.pageOverFacet(addToList, facet, query, Array())

    resultsList
  }

}