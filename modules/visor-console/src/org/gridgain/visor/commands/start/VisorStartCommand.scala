/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*
 * ___    _________________________ ________
 * __ |  / /____  _/__  ___/__  __ \___  __ \
 * __ | / /  __  /  _____ \ _  / / /__  /_/ /
 * __ |/ /  __/ /   ____/ / / /_/ / _  _, _/
 * _____/   /___/   /____/  \____/  /_/ |_|
 *
 */

package org.gridgain.visor.commands.start

import org.gridgain.scalar._
import scalar._
import org.gridgain.visor._
import org.gridgain.visor.commands.{VisorConsoleCommand, VisorTextTable}
import visor._
import java.io._
import scala.util.control.Breaks._
import scala.collection._
import org.gridgain.grid._
import java.util.concurrent._
import collection.JavaConversions._

/**
 * Node start attempt result.
 */
private case class Result(
    host: String,
    ok: Boolean,
    errMsg: String = null
) {
    assert(host != null)
}

/**
 * ==Overview==
 * Contains Visor command `start` implementation.
 *
 * ==Importing==
 * When using this command from Scala code (not from REPL) you need to make sure to
 * properly import all necessary typed and implicit conversions:
 * <ex>
 * import org.gridgain.visor._
 * import commands.start.VisorStartCommand._
 * </ex>
 * Note that `VisorStartCommand` object contains necessary implicit conversions so that
 * this command would be available via `visor` keyword.
 *
 * ==Help==
 * {{{
 * +-----------------------------------------------------+
 * | start | Starts one or more nodes on remote host(s). |
 * |       | Uses SSH protocol to execute commands.      |
 * +-----------------------------------------------------+
 * }}}
 *
 * ====Specification====
 * {{{
 *     start "-f=<path> {-m=<num>} {-r}"
 *     start "-h=<hostname> {-p=<num>} {-u=<username>} {-pw=<password>} {-k=<path>}
 *         {-n=<num>} {-g=<path>} {-c=<path>} {-s=<path>} {-m=<num>} {-r}"
 * }}}
 *
 * ====Arguments====
 * {{{
 *     -f=<path>
 *         Path to INI file that contains topology specification.
 *     -h=<hostname>
 *         Hostname where to start nodes.
 *
 *         Can define several hosts if their IPs are sequential.
 *         Example of range is 192.168.1.100~150,
 *         which means all IPs from 192.168.1.100 to 192.168.1.150 inclusively.
 *     -p=<num>
 *         Port number (default is 22).
 *     -u=<username>
 *         Username (if not defined, current local username will be used).
 *     -pw=<password>
 *         Password (if not defined, private key file must be defined).
 *     -k=<path>
 *         Path to private key file. Define if key authentication is used.
 *     -n=<num>
 *         Expected number of nodes on the host.
 *         If some nodes are started already, then only remaining nodes will be started.
 *         If current count of nodes is equal to this number and '-r' flag is not set, then nothing will happen.
 *     -g=<path>
 *         Path to GridGain installation folder.
 *         If not defined, GRIDGAIN_HOME environment variable must be set on remote hosts.
 *     -c=<path>
 *         Path to configuration file (relative to GridGain home).
 *         If not provided, default GridGain configuration is used.
 *     -s=<path>
 *         Path to start script (relative to GridGain home).
 *         Default is "bin/ggstart.sh" for Unix or
 *         "bin\ggstart.bat" for Windows.
 *     -m=<num>
 *         Defines maximum number of nodes that can be started in parallel on one host.
 *         This actually means number of parallel SSH connections to each SSH server.
 *         Default is 5.
 *     -t=<num>
 *         Defines connection timeout in milliseconds.
 *         Default is 2000.
 *     -r
 *         Indicates that existing nodes on the host will be restarted.
 *         By default, if flag is not present, existing nodes will be left as is.
 * }}}
 *
 * ====Examples====
 * {{{
 *     start "-h=10.1.1.10 -u=uname -pw=passwd -n=3"
 *         Starts three nodes with default configuration (password authentication).
 *     start "-h=192.168.1.100~104 -u=uname -k=/home/uname/.ssh/is_rsa -n=5"
 *         Starts 25 nodes on 5 hosts (5 nodes per host) with default configuration (key-based authentication).
 *     start "-f=start-nodes.ini -r"
 *         Starts topology defined in 'start-nodes.ini' file. Existing nodes are stopped.
 * }}}
 */
class VisorStartCommand {
    /** Default maximum number of parallel connections. */
    private final val DFLT_MAX_CONN = 5

    /** Default connection timeout. */
    private final val DFLT_TIMEOUT = 2000

    /**
     * Prints error message and advise.
     *
     * @param errMsgs Error messages.
     */
    private def scold(errMsgs: Any*) {
        assert(errMsgs != null)

        nl()

        warn(errMsgs: _*)
        warn("Type 'help start' to see how to use this command.")
    }

    /**
     * Catch point for missing arguments case.
     */
    def start() {
        scold("Missing arguments.")
    }

    /**
     * ===Command===
     * Starts or restart one or more nodes on remote host.
     * Uses SSH protocol to execute commands.
     *
     * ===Examples===
     * <ex>start "-h=uname:passwd@host#3"</ex>
     * Starts three nodes with default configuration (password authentication).
     *
     * <ex>start "-h=uname@host#3 -k=ssh-key.pem"</ex>
     * Starts three nodes with default configuration (key authentication).
     *
     * <ex>start "-f=hosts.txt -c=config/spring.xml"</ex>
     * Reads `hosts.txt` file and starts nodes with provided configuration.
     *
     * @param args Command arguments.
     */
    def start(args: String) = breakable {
        assert(args != null)

        if (!isConnected)
            adviseToConnect()
        else {
            val argLst = parseArgs(args)

            val fileOpt = argValue("f", argLst)
            val hostOpt = argValue("h", argLst)
            val portOpt = argValue("p", argLst)
            val unameOpt = argValue("u", argLst)
            val passwdOpt = argValue("pw", argLst)
            val keyOpt = argValue("k", argLst)
            val nodesOpt = argValue("n", argLst)
            val ggHomeOpt = argValue("g", argLst)
            val cfgOpt = argValue("c", argLst)
            val scriptOpt = argValue("s", argLst)
            val maxConnOpt = argValue("m", argLst)
            val timeoutOpt = argValue("t", argLst)
            val restart = hasArgFlag("r", argLst)

            val maxConn = maxConnOpt match {
                case None => DFLT_MAX_CONN
                case Some(mc) =>
                    try {
                        mc.toInt
                    }
                    catch {
                        case e: NumberFormatException =>
                            scold("Invalid maximum number of parallel connections: " + maxConnOpt.get) ^^

                        0 // Never happens.
                    }
            }

            if (maxConn <= 0)
                scold("Invalid maximum number of parallel connections: " + maxConn) ^^

            val timeout = timeoutOpt match {
                case None => DFLT_TIMEOUT
                case Some(to) =>
                    try {
                        to.toInt
                    }
                    catch {
                        case e: NumberFormatException =>
                            scold("Invalid timeout: " + to) ^^

                        0 // Never happens.
                    }
            }

            if (timeout <= 0)
                scold("Invalid connection timeout: " + timeout) ^^

            var res = Seq.empty[Result]

            if (fileOpt.isDefined) {
                val file = new File(fileOpt.get)

                try
                    res = grid.startNodes(file, restart, timeout, maxConn).get().map(t => {
                        Result(t.get1, t.get2, t.get3)
                    }).toSeq
                catch {
                    case e: GridException => scold(e.getMessage) ^^
                    case _: RejectedExecutionException => scold("Failed due to system error.") ^^
                }
            }
            else {
                if (hostOpt.isEmpty)
                    scold("Hostname is required.") ^^

                val port: java.lang.Integer =
                    try {
                        if (portOpt.isDefined) portOpt.get.toInt else null.asInstanceOf[java.lang.Integer]
                    }
                    catch {
                        case e: NumberFormatException => scold("Invalid port number: " + portOpt.get) ^^

                        0 // Never happens.
                    }

                if (port != null && port <= 0)
                    scold("Invalid port number: " + port) ^^

                val keyFile = if (keyOpt.isDefined) new File(keyOpt.get) else null

                if (keyFile != null && (!keyFile.exists || !keyFile.isFile))
                    scold("File not found: " + keyFile.getAbsolutePath) ^^

                val nodes: java.lang.Integer =
                    try {
                        if (nodesOpt.isDefined) nodesOpt.get.toInt else null.asInstanceOf[java.lang.Integer]
                    }
                    catch {
                        case e: NumberFormatException => scold("Invalid number of nodes: " + nodesOpt.get) ^^

                        0 // Never happens.
                    }

                if (nodes != null && nodes <= 0)
                    scold("Invalid number of nodes: " + nodes) ^^

                val params: Map[String, AnyRef] = Map(
                    "host" -> hostOpt.get,
                    "port" -> port,
                    "uname" -> (unameOpt getOrElse null),
                    "passwd" -> (passwdOpt getOrElse null),
                    "key" -> keyFile,
                    "nodes" -> nodes,
                    "ggHome" -> (ggHomeOpt getOrElse null),
                    "cfg" -> (cfgOpt getOrElse null),
                    "script" -> (scriptOpt getOrElse null)
                )

                try
                    res = grid.startNodes(
                        toJavaCollection(Seq(params)), null, restart, timeout, maxConn).get().map(t => {
                            Result(t.get1, t.get2, t.get3)
                        }).toSeq
                catch {
                    case e: GridException => scold(e.getMessage) ^^
                    case _: RejectedExecutionException => scold("Failed due to system error.") ^^
                }
            }

            val resT = VisorTextTable()

            resT += ("Successful start attempts", res.count(_.ok))
            resT += ("Failed start attempts", res.count(!_.ok))

            resT.render()

            if (res.exists(!_.ok)) {
                nl()

                println("Errors:")

                val errT = VisorTextTable()

                errT.maxCellWidth = 70

                errT #= ("Host", "Error")

                res.filter(!_.ok) foreach (r => {
                    errT += (r.host, r.errMsg)
                })

                errT.render()
            }

            nl()

            println("Type 'top' to see current topology.")

            nl()

            println("NOTE:")
            println("    - Successful start attempt DOES NOT mean that node actually started.")
            println("    - For large topologies (> 100s nodes) it can take over 10 minutes for all nodes to start.")
            println("    - See individual node log for details.")
        }
    }
}

/**
 * Companion object that does initialization of the command.
 */
object VisorStartCommand {
    addHelp(
        name = "start",
        shortInfo = "Starts or restarts nodes on remote hosts.",
        longInfo = List(
            "Starts one or more nodes on remote host(s).",
            "Uses SSH protocol to execute commands."
        ),
        spec = List(
            "start -f=<path> {-m=<num>} {-r}",
            "start -h=<hostname> {-p=<num>} {-u=<username>} {-pw=<password>} {-k=<path>}",
                "{-n=<num>} {-g=<path>} {-c=<path>} {-s=<path>} {-m=<num>} {-r}"
        ),
        args = List(
            "-f=<path>" -> List(
                "Path to INI file that contains topology specification."
            ),
            "-h=<hostname>" -> List(
                "Hostname where to start nodes.",
                " ",
                "Can define several hosts if their IPs are sequential.",
                "Example of range is 192.168.1.100~150,",
                "which means all IPs from 192.168.1.100 to 192.168.1.150 inclusively."
            ),
            "-p=<num>" -> List(
                "Port number (default is 22)."
            ),
            "-u=<username>" -> List(
                "Username (if not defined, current local username will be used)."
            ),
            "-pw=<password>" -> List(
                "Password (if not defined, private key file must be defined)."
            ),
            "-k=<path>" -> List(
                "Path to private key file. Define if key authentication is used."
            ),
            "-n=<num>" -> List(
                "Expected number of nodes on the host.",
                "If some nodes are started already, then only remaining nodes will be started.",
                "If current count of nodes is equal to this number and '-r' flag is not set, then nothing will happen."
            ),
            "-g=<path>" -> List(
                "Path to GridGain installation folder.",
                "If not defined, GRIDGAIN_HOME environment variable must be set on remote hosts."
            ),
            "-c=<path>" -> List(
                "Path to configuration file (relative to GridGain home).",
                "If not provided, default GridGain configuration is used."
            ),
            "-s=<path>" -> List(
                "Path to start script (relative to GridGain home).",
                "Default is \"bin/ggstart.sh\" for Unix or",
                "\"bin\\ggstart.bat\" for Windows."
            ),
            "-m=<num>" -> List(
                "Defines maximum number of nodes that can be started in parallel on one host.",
                "This actually means number of parallel SSH connections to each SSH server.",
                "Default is 5."
            ),
            "-t=<num>" -> List(
                "Defines connection timeout in milliseconds.",
                "Default is 2000."
            ),
            "-r" -> List(
                "Indicates that existing nodes on the host will be restarted.",
                "By default, if flag is not present, existing nodes will be left as is."
            )
        ),
        examples = List(
            "start -h=10.1.1.10 -u=uname -pw=passwd -n=3" ->
                "Starts three nodes with default configuration (password authentication).",
            "start -h=192.168.1.100~104 -u=uname -k=/home/uname/.ssh/is_rsa -n=5" ->
                "Starts 25 nodes on 5 hosts (5 nodes per host) with default configuration (key-based authentication).",
            "start -f=start-nodes.ini -r" ->
                "Starts topology defined in 'start-nodes.ini' file. Existing nodes are stopped."
        ),
        ref = VisorConsoleCommand(cmd.start, cmd.start)
    )

    /** Singleton command. */
    private val cmd = new VisorStartCommand

    /**
     * Singleton.
     */
    def apply() = cmd

    /**
     * Implicit converter from visor to commands "pimp".
     *
     * @param vs Visor tagging trait.
     */
    implicit def fromStart2Visor(vs: VisorTag) = cmd
}
