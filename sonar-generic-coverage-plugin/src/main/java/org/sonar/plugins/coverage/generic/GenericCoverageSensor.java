/*
 * SonarQube Generic Coverage Plugin
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.coverage.generic;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.Project;

import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.util.List;

public class GenericCoverageSensor implements Sensor {

  private final Settings settings;
  private final FileSystem fs;
  private final ResourcePerspectives perspectives;

  public GenericCoverageSensor(Settings settings, FileSystem fs, ResourcePerspectives perspectives) {
    this.settings = settings;
    this.fs = fs;
    this.perspectives = perspectives;
  }

  @Override
  public boolean shouldExecuteOnProject(Project project) {
    return StringUtils.isNotEmpty(coverageReportPath(null)) || StringUtils.isNotEmpty(itReportPath()) ||
      StringUtils.isNotEmpty(overallReportPath()) || StringUtils.isNotEmpty(unitTestReportPath());
  }

  private String coverageReportPath(@Nullable Logger logger) {
    String result = settings.getString(GenericCoveragePlugin.OLD_REPORT_PATH_PROPERTY_KEY);
    if (StringUtils.isEmpty(result)) {
      result = settings.getString(GenericCoveragePlugin.COVERAGE_REPORT_PATHS_PROPERTY_KEY);
    } else if (logger != null) {
      logDeprecatedPropertyUsage(logger, GenericCoveragePlugin.COVERAGE_REPORT_PATHS_PROPERTY_KEY, GenericCoveragePlugin.OLD_REPORT_PATH_PROPERTY_KEY);
    }
    return result;
  }

  private static void logDeprecatedPropertyUsage(Logger logger, String newPropertyKey, String oldProperty) {
    logger.warn("Use the new property \"" + newPropertyKey + "\" instead of the deprecated \"" + oldProperty + "\"");
  }

  private String itReportPath() {
    return settings.getString(GenericCoveragePlugin.IT_COVERAGE_REPORT_PATHS_PROPERTY_KEY);
  }

  private String overallReportPath() {
    return settings.getString(GenericCoveragePlugin.OVERALL_COVERAGE_REPORT_PATHS_PROPERTY_KEY);
  }

  private String unitTestReportPath() {
    return settings.getString(GenericCoveragePlugin.UNIT_TEST_REPORT_PATHS_PROPERTY_KEY);
  }

  private static List<String> getList(@Nullable String string) {
    return string == null ? ImmutableList.<String>of() : Lists.newArrayList(Splitter.on(",").trimResults().omitEmptyStrings().split(string));
  }

  @Override
  public void analyse(Project project, SensorContext context) {
    analyseWithLogger(context, LoggerFactory.getLogger(GenericCoverageSensor.class));
  }

  public void analyseWithLogger(SensorContext context, Logger logger) {
    boolean ok = loadReport(context, logger, ReportParser.Mode.COVERAGE, coverageReportPath(logger));
    if (ok) {
      ok = loadReport(context, logger, ReportParser.Mode.IT_COVERAGE, itReportPath());
    }
    if (ok) {
      ok = loadReport(context, logger, ReportParser.Mode.OVERALL_COVERAGE, overallReportPath());
    }
    if (ok) {
      loadReport(context, logger, ReportParser.Mode.UNITTEST, unitTestReportPath());
    }
  }

  private boolean loadReport(SensorContext context, Logger logger, ReportParser.Mode mode, String reportPath) {
    String modeString = getModeString(mode);
    ReportParser parser = new ReportParser(fs, context, perspectives, mode);
    List<String> strings = getList(reportPath);
    while (!strings.isEmpty()) {
      String path = strings.remove(0);
      File reportFile = new File(path);
      if (!reportFile.isAbsolute()) {
        reportFile = new File(fs.baseDir(), path);
      }
      String reportAbsolutePath = reportFile.getAbsolutePath();
      logger.info("Parsing " + reportAbsolutePath);

      if (!reportFile.exists()) {
        logger.warn("Cannot find " + modeString + " report to parse: " + reportAbsolutePath);
        return false;
      }

      try {
        parser.parse(reportFile, path);
      } catch (XMLStreamException e) {
        throw new IllegalStateException("Cannot parse " + modeString + " report " + reportAbsolutePath, e);
      } catch (ReportParsingException e) {
        throw new IllegalStateException("Error at line " + e.lineNumber() + " of " + modeString + " report " + reportAbsolutePath, e);
      }
    }
    parser.saveMeasures();

    logger.info("Imported " + modeString + " data for " + parser.numberOfMatchedFiles() + " files");
    int numberOfUnknownFiles = parser.numberOfUnknownFiles();
    if (numberOfUnknownFiles > 0) {
      String fileList = Joiner.on("\n").join(parser.firstUnknownFiles());
      logger.info(modeString + " data ignored for " + numberOfUnknownFiles + " unknown files, including:\n" + fileList);
    }
    return true;
  }

  private static String getModeString(ReportParser.Mode mode) {
    switch (mode) {
      case COVERAGE:
        return "coverage";
      case IT_COVERAGE:
        return "IT coverage";
      case OVERALL_COVERAGE:
        return "Overall coverage";
      default:
        return "unit test";
    }
  }

  @Override
  public String toString() {
    return "GenericCoverageSensor";
  }

}
