package org.transmartfoundation

import grails.build.logging.GrailsConsole
import grails.util.Environment
import groovy.json.JsonSlurper

import java.util.regex.Matcher

class DependencyManagement {

    private static final String CONFIG_JSON_FILE = 'config.json'

    File topDirectory
    Map config
    Map<String, Object> plugins = [:]

    DependencyManagement() {
        try {
            config = loadConfigFile()
        } catch (IOException ioException) {
            throw new IOException('Could not load config.json', ioException)
        }
    }

    Map loadConfigFile() {
        // look for the version file by going up ${user.dir}
        topDirectory = new File(System.getProperty('user.dir')).parentFile
        File configJsonFile
        while (!(configJsonFile = new File(topDirectory, CONFIG_JSON_FILE)).isFile()) {
            topDirectory = topDirectory.parentFile
            if (topDirectory == null) {
                throw new FileNotFoundException("File ${CONFIG_JSON_FILE} " +
                        "not found in one of the parent directories of " +
                        "${System.getProperty('user.dir')}")
            }
        }

        def slurper = new JsonSlurper()
        configJsonFile.withReader {
            slurper.parse it
        }
    }

    void configureRepositories(configurer) {
        configurer.legacyResolve true // for secondary resolution in inline plugins

        configurer.repositories {
            mavenLocal()
            grailsCentral()
            grailsCentral()

            config.REPOSITORIES.each { repoUrl ->
                mavenRepo repoUrl
            }
        }
    }

    void configureInternalPlugin(String scope, String name, Closure closure = {}) {
        String directoryName = (config.'SPECIAL-LOCATIONS')[name] ?: name
        def location = new File(topDirectory, directoryName)
        if (location.isDirectory()) {
            plugins[name] = [
                    scope:    scope,
                    closure:  closure,
                    version:  getPluginVersion(name, location),
                    location: location,
            ]
        } else {
            throw new FileNotFoundException("Could not find plugin $name at $location")
        }
    }

    String getPluginVersion(String name, File location) {
        String descriptorFileName = name.
                replaceAll(/(?:^|-)[a-z]/, { (it - '-').toUpperCase() } ) +
                'GrailsPlugin.groovy'

        File descriptorFile = new File(location, descriptorFileName)
        if (!descriptorFile.isFile()) {
            throw new FileNotFoundException("Could not find descriptor for " +
                    "plugin $name at $descriptorFile")
        }

        // we can't compile the class because it probably has symbols that we
        // could only find on the plugin's dependencies
        // we could go for GroovyLexer or something even more higher level,
        // but for now use regex, because it's simpler
        def result = descriptorFile.withReader { BufferedReader reader ->
            def line
            while ((line = reader.readLine()) != null) {
                Matcher regexMatcher = line =~ /(?:def|String)\s+version\s+=\s+("|')(.+?)\1/
                if (regexMatcher.find()) {
                    return regexMatcher.group(2);
                }
            }
        }

        if (!result) {
            throw new RuntimeException("Could not find version in $descriptorFile")
        }
        result
    }

    void internalDependencies(originalDelegate) {
        plugins.each { String name, Map properties ->
            originalDelegate."$properties.scope"(
                    ":$name:$properties.version",
                    properties.closure)
        }
    }

    void inlineInternalDependencies(grailsConfigKey, grailsSettings) {
        plugins.each { String name, Map p ->
            //println "Loading in-place plugin $name ($p.scope/$p.version) from $p.location"
            grailsConfigKey.plugin.location."$name" = p.location.absolutePath

            if (grailsSettings.projectPluginsDir?.exists()) {
                grailsSettings.projectPluginsDir.eachDir { dir ->
                    // remove optional version from inline definition
                    def dirPrefix = name.replaceFirst(/:.+/, '') + '-'
                    if (!dir.name.startsWith(dirPrefix)) {
                        return
                    }

                    def msg = "Found a plugin directory at $dir that is a " +
                                "possible conflict and may prevent grails from using " +
                                "the in-place $name plugin."

                    if (Environment.isWithinShell()) {
                        GrailsConsole.instance.warn msg
                    } else {
                        println "[WARN]: $msg"
                    }
                }
            }
        }
    }
}
