package org.example.project.junit.xmlreport

import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Date
import java.util.Enumeration
import java.util.Properties
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/* Schmant, the build tool, http://www.schmant.org
 * Copyright (C) 2007, 2008 Karl Gustafsson, Holocene Software,
 * http://www.holocene.se
 *
 * Schmant is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Schmant is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
/* This class contains code copied from Apache Ant's
 * org.apache.tools.ant.taskdefs.optional.junit.XMLJUnitResultFormatter class
 * from Ant verson 1.7.1.
 */


/**
 * This [RunListener] produces an XML report formatted like Ant's JUnit
 * XML report.
 *
 * @author Karl Gustafsson
 * @taskpackage org.junit.junit4
 * @since 0.9
 */
class AntXmlRunListener(cbPrint:(line:String)->Unit, private val props: Properties, finishHandler:()->Unit) : RunListener() {
    private var outputStream: OutputStream? = null
    private val print_=cbPrint;
    private val finishHandler = finishHandler;

    /**
     * The XML document.
     */
    private var m_doc: Document? = null

    /**
     * The wrapper for the whole testsuite.
     */
    private var m_rootElement: Element? = null

    /**
     * Mapping between test Description:s -> Start timestamp (Long)
     */
    private val m_testStarts: MutableMap<Any?, Any?> = mutableMapOf();//HashMap<Any?, Any?>()

    /**
     * Mapping between test Description:s -> Failure objects
     */
    private val m_failedTests: MutableMap<Any?, Any?> = mutableMapOf();// HashMap<Any?, Any?>()

    /**
     * Mapping between test Description:s -> XML Element:s
     */
    private val m_testElements: MutableMap<Any?, Any?> = mutableMapOf();//HashMap<Any?, Any?>()

    init {
        val filePath = System.getProperty("org.schmant.task.junit4.target")
        if (filePath != null) {
            val target = File(filePath)
            try {
                outputStream = BufferedOutputStream(FileOutputStream(target))
            } catch (e: FileNotFoundException) {
                throw RuntimeException(e)
            }
        }
    }

    fun setOutputStream(outputStream: OutputStream?) {
        this.outputStream = outputStream
    }

    private val hostname: String
        /**
         * get the local hostname
         *
         * @return the name of the local host, or "localhost" if we cannot work it out
         */
        private get() = try {
            InetAddress.getLocalHost().hostName
        } catch (e: UnknownHostException) {
            "localhost"
        }

    /**
     * The whole test suite started.
     *
     * @param descr The test suite description.
     */
    override fun testRunStarted(descr: Description) {
        m_doc = documentBuilder.newDocument()
        m_rootElement = m_doc!!.createElement(TESTSUITE)
        val n = descr.displayName
        m_rootElement!!.setAttribute(ATTR_NAME, n ?: "unknown")

        //add the timestamp
        val timestamp: String = DateUtils.format(Date(), DateUtils.ISO8601_DATETIME_PATTERN)
        m_rootElement!!.setAttribute(TIMESTAMP, timestamp)
        //and the hostname.
        m_rootElement!!.setAttribute(HOSTNAME, hostname)

        // Output properties
        val propsElement = m_doc!!.createElement(PROPERTIES)
        m_rootElement!!.appendChild(propsElement)

        if (props.size>=0)
        {
            val e: Enumeration<*>? = props.propertyNames();
            while (e!!.hasMoreElements())
            {

                val name =  e.nextElement() as String
                val propElement = m_doc!!.createElement(PROPERTY);
                print(name)
                propElement.setAttribute(ATTR_NAME, name);
                propElement.setAttribute(ATTR_VALUE, props.getProperty(name).trimIndent());
                propsElement.appendChild(propElement);
            }
        }
    }

    /**
     * Interface TestListener.
     *
     *
     *
     * A new Test is started.
     *
     * @param descr The test description.
     */
    override fun testStarted(descr: Description) {
        m_testStarts[descr] = System.currentTimeMillis()
    }

    private fun formatError(type: String, f: Failure) {
        testFinished(f.description)
        m_failedTests[f.description] = f
        val nested = m_doc!!.createElement(type)
        val currentTest = m_testElements[f.description] as Element?
        currentTest!!.appendChild(nested)
        val message = f.message
        if (message != null && message.length > 0) {
            nested.setAttribute(ATTR_MESSAGE, message)
        }
        nested.setAttribute(ATTR_TYPE, f.description.displayName)
        val strace = getFilteredTrace(f.exception)
        val trace = m_doc!!.createTextNode(strace)
        nested.appendChild(trace)
    }

    /**
     * Interface TestListener for JUnit &lt;= 3.4.
     *
     *
     *
     * A Test failed.
     *
     * @param f The failure.
     */
    override fun testFailure(f: Failure) {
        formatError(FAILURE, f)
    }

    override fun testAssumptionFailure(f: Failure) {
        formatError(FAILURE, f)
    }

    /**
     * Interface TestListener.
     *
     *
     *
     * A Test is finished.
     *
     * @param descr The test description.
     */
    override fun testFinished(descr: Description) {
        // Fix for bug #5637 - if a junit.extensions.TestSetup is
        // used and throws an exception during setUp then startTest
        // would never have been called
        if (!m_testStarts.containsKey(descr)) {
            testStarted(descr)
        }
        var currentTest: Element? = null
        if (!m_failedTests.containsKey(descr)) {
            //			Test test = (Test) descr.getAnnotation(Test.class);
            currentTest = m_doc!!.createElement(TESTCASE)
            val n: String = getTestCaseName(descr.displayName)
            currentTest.setAttribute(ATTR_NAME, n ?: "unknown")
            // a TestSuite can contain Tests from multiple classes,
            // even tests with the same name - disambiguate them.
            currentTest.setAttribute(ATTR_CLASSNAME, getTestCaseClassName(descr.displayName))
            m_rootElement!!.appendChild(currentTest)
            m_testElements[descr] = currentTest
        } else {
            currentTest = m_testElements[descr] as Element?
        }
        val l = m_testStarts[descr] as Long?
        currentTest!!.setAttribute(
            ATTR_TIME,
            "" + (System.currentTimeMillis() - l!!) / ONE_SECOND
        )

        //Add System Out and System Error here
        //<system-out><![CDATA[==========================================
        //<system-err><![CDATA[==========================================

    }

    /**
     * The whole test suite ended.
     *
     * @param result The test suite result.
     * @throws BuildException on error.
     */
    override fun testRunFinished(result: Result) {
        try {

            m_rootElement!!.setAttribute(ATTR_TESTS, "" + result.runCount)
            m_rootElement!!.setAttribute(ATTR_FAILURES, "" + result.failureCount)
            // JUnit4 does not seem to discern between failures and errors.
            m_rootElement!!.setAttribute(ATTR_ERRORS, "" + 0)
            m_rootElement!!.setAttribute(
                ATTR_TIME,
                "" + result.runTime / ONE_SECOND
            )

            setSystemOutput(systemOutList.joinToString("\r\n"))
            setSystemError("")

            val wri: Writer = BufferedWriter(OutputStreamWriter(outputStream, "UTF8"))
            wri.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
            DOMElementWriter().write(m_rootElement!!, wri, 0, "  ")
            wri.flush()
            //logging("[[The test has been finished]]")
        } catch (exc: IOException) {
            throw RuntimeException("Unable to write log file", exc)
        }  finally {
            outputStream!!.close()
            this.finishHandler();
        }
    }

//    @Throws(Throwable::class)
//    protected fun finalize() {
//        if (outputStream != null) {
//            outputStream!!.close()
//        }
//        //super.finalize()
//        //super.
//    } //

    //	/**
    //	 * Where to write the log to.
    //	 */
    //	private OutputStream out;
    //
    //	/** {@inheritDoc}. */
    //	public void setOutput(OutputStream out)
    //	{
    //		this.out = out;
    //	}
    //
    /** {@inheritDoc}. */
    fun setSystemOutput(out_:String)
    {
        formatOutput(SYSTEM_OUT, out_);
        systemOutList.clear()
    }

    /** {@inheritDoc}. */
    fun setSystemError(out_:String)
    {
        formatOutput(SYSTEM_ERR, out_);
    }

    private fun formatOutput(type:String,output:String)
    {
        val nested:Element = m_doc!!.createElement(type);
        m_rootElement!!.appendChild(nested);
        nested.appendChild(m_doc!!.createCDATASection(output));
    }

    var systemOutList:MutableList<String> = mutableListOf()
    fun appendSystemOut(line: String) {
        systemOutList.add(line);
    }
//    fun clearSystemOut(line: String) {
//        systemOutList.clear()
//    }

    companion object {
        private const val ONE_SECOND = 1000.0
        // XML constants
        /**
         * the testsuite element
         */
        private const val TESTSUITE = "testsuite"

        /**
         * the testcase element
         */
        private const val TESTCASE = "testcase"

        /**
         * the failure element
         */
        private const val FAILURE = "failure"
        /** the system-err element  */ //	private static String SYSTEM_ERR = "system-err";
        /** the system-out element  */ //	private static String SYSTEM_OUT = "system-out";
        /**
         * name attribute for property, testcase and testsuite elements
         */
        private const val ATTR_NAME = "name"

        /**
         * time attribute for testcase and testsuite elements
         */
        private const val ATTR_TIME = "time"

        /**
         * errors attribute for testsuite elements
         */
        private const val ATTR_ERRORS = "errors"

        /**
         * failures attribute for testsuite elements
         */
        private const val ATTR_FAILURES = "failures"

        /**
         * tests attribute for testsuite elements
         */
        private const val ATTR_TESTS = "tests"

        /**
         * type attribute for failure and error elements
         */
        private const val ATTR_TYPE = "type"

        /**
         * message attribute for failure elements
         */
        private const val ATTR_MESSAGE = "message"

        /**
         * the properties element
         */
        private const val PROPERTIES = "properties"

        /**
         * classname attribute for testcase elements
         */
        private const val ATTR_CLASSNAME = "classname"

        /**
         * timestamp of test cases
         */
        private const val TIMESTAMP = "timestamp"

        /**
         * name of host running the tests
         */
        private const val HOSTNAME = "hostname"


        private const val PROPERTY = "property"
        private const val ATTR_VALUE = "value"

        private const val SYSTEM_OUT = "system-out"
        private const val SYSTEM_ERR = "system-err"

        /**
         * Convenient method to retrieve the full stacktrace from a given exception.
         *
         * @param t the exception to get the stacktrace from.
         * @return the stacktrace from the given exception.
         */
        private fun getStackTrace(t: Throwable): String {
            val sw = StringWriter()
            val pw = PrintWriter(sw, true)
            t.printStackTrace(pw)
            pw.flush()
            pw.close()
            return sw.toString()
        }

        /**
         * Returns a filtered stack trace.
         * This is ripped out of junit.runner.BaseTestRunner.
         *
         * @param t the exception to filter.
         * @return the filtered stack trace.
         */
        private fun getFilteredTrace(t: Throwable): String {
            return filterStack(getStackTrace(t))
        }

        /**
         * Filters stack frames from internal JUnit and Ant classes
         *
         * @param stack the stack trace to filter.
         * @return the filtered stack.
         */
        fun filterStack(stack: String): String {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            val sr = StringReader(stack)
            val br = BufferedReader(sr)
            var line: String
            try {
                while (br.readLine().also { line = it } != null) {
                    if (!filterLine(line)) {
                        pw.println(line)
                    }
                }
            } catch (e: Exception) {
                return stack // return the stack unfiltered
            }
            return sw.toString()
        }

        private val DEFAULT_TRACE_FILTERS = arrayOf(
            "junit.framework.TestCase",
            "junit.framework.TestResult",
            "junit.framework.TestSuite",
            "junit.framework.Assert.",  // don't filter AssertionFailure
            "junit.swingui.TestRunner",
            "junit.awtui.TestRunner",
            "junit.textui.TestRunner",
            "java.lang.reflect.Method.invoke(",
            "sun.reflect.",
            "org.apache.tools.ant.",  // JUnit 4 support:
            "org.junit.",
            "junit.framework.JUnit4TestAdapter",  // See wrapListener for reason:
            "Caused by: java.lang.AssertionError",
            " more"
        )

        private fun filterLine(line: String): Boolean {
            for (i in DEFAULT_TRACE_FILTERS.indices) {
                if (line.indexOf(DEFAULT_TRACE_FILTERS[i]) != -1) {
                    return true
                }
            }
            return false
        }

        private fun getTestCaseName(s: String?): String {
            if (s == null) {
                return "unknown"
            }
            return if (s.endsWith(")")) {
                val paren = s.lastIndexOf('(')
                s.substring(0, paren)
            } else {
                s
            }
        }

        private fun getTestCaseClassName(s: String?): String {
            if (s == null) {
                return "unknown"
            }

            // JUnit 4 wraps solo tests this way. We can extract
            // the original test name with a little hack.
            val paren = s.lastIndexOf('(')
            return if (paren != -1 && s.endsWith(")")) {
                s.substring(paren + 1, s.length - 1)
            } else {
                s
            }
        }

        private val documentBuilder: DocumentBuilder
            private get() {
                return try {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
    }
}