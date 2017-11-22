#!/usr/bin/env groovy

//
// Typical usage:
//
// Recoder.groovy -d -o +.mkv -v 0 -a 1 -s 3 -b 00:01:06 -e 00:51:20 -x /mnt/Media/TV/Steel_S01E03_Tropics.m2t -o +.mkv -v 0 -a 1 -s 3 -b 00:01:06 -e 00:51:20 -x -d
//
// which (since it is dryrun) prints this line:
//
// ffmpeg -ss 00:01:06 -i "/mnt/Media/TV/Steel_S01E03_Tropics.m2t" -map 0:0 -vcodec h264 -map 0:1 -acodec copy -map 0:3 -scodec copy -t 00:50:14 "/mnt/Media/TV/Steel_S01E03_Tropics.mkv"
//

@Grapes(
    @Grab(group='org.apache.commons', module='commons-io', version='1.3.2')
)
import org.apache.commons.io.FilenameUtils as FU
import java.text.DecimalFormat

class Recoder {

	public static void main(String[] args) {
		def cli = new CliBuilder(usage: 'Recoder.groovy [ -h ] [ -d ] [ -s ] [ -o <file or ext> ] [ -b <time> ] [ -e <time> ]  [ -v <index> ] [ -a <index> ] [ -s <index> ] [ -z ] [ -x ] <files>')
		cli.with {
		    h longOpt: 'help', 'Show usage information and quit'
		    d longOpt: 'dryrun',
		            'Print the commands that would be executed without actually executing them'
		    o longOpt: 'output', args: 1, argName: 'file',
		            'Output file (extension determines container). If a folder is given output is stored with the input filename in the given folder. If just an extension (e.g. .mkv) is given the input filename with the given extension is used as output (in either the same folder as the input, of if the extension is prefixed with a path in the folder given by the path, e.g. /tmp/.mp4). If prefixed with + the file is relative to the folder of input, e.g. +.mp4 will place a mp4 file in the input folder' 
		    b longOpt: 'begin', args: 1, argName: 'time',
		            'If set this marks the time (hh:mm:ss) - in relation to the input - at which to begin the output (default is from the start of input)'
		    e longOpt: 'end', args: 1, argName: 'time',
		            'If set this marks the time (hh:mm:ss) - in relation to the input - at which to end the output (default is until the end of input)'
		    v longOpt: 'video', args: 1, argName: 'index',
		            'If given it is the index of the video stream (counting from 0)'
		    a longOpt: 'audio', args: 1, argName: 'index',
		            'If given it is the index of the audio stream (counting from 0)'
		    s longOpt: 'subtitle', args: 1, argName: 'index',
		            'If given it is the index of the subtitle stream (counting from 0)'
		    z longOpt: 'streams',
		            'If only the input file is given this print the streams found in that file and quits. If other parameters are given this will attempt to auto-detect the streams (if a stream type is explicitly given on command line, e.g. audio, then that is the only audio included even if input holds say stereo and 5.1 streams)'
		    x longOpt: 'transcode',
		            'If given video stream will be encoded with h264, otherwise it will just be copied (as all other streams)'
		    c longOpt: 'concat',
		            'If given the videos listed will be concatenated into one output video - if no output file is given then a file "concat" with appropriate extension (taken from input) is created'
		}
		
		def opt = cli.parse(args);
		
		//
		// Sanitize parameters (and collect list of input files)
		//
		
		if (!opt) {
			println "Invalid parameter(s)"
			cli.usage()
			System.exit(1)
		}
		else if (opt.help) {
			cli.usage()
			System.exit(0)
		}
		
		def inputs = opt.arguments()
		if(!inputs || (opt.streams && !inputs) || (inputs && !opt.output && !opt.streams && !opt.concat)) {
			cli.usage()
			System.exit(2)
		}
		
		//
		// Perform the requested action 
		//
		
		if (opt.concat) {
			// Don't consider other options then - just concat the given inputs and copy to one output
			def (String output, List<String>list) = concatList(opt.output ?: null, inputs)
			
			File temp = File.createTempFile("recoder-concat-", ".txt")
			list.each {
				temp << "${it}\n"
			}
			def cmd = "ffmpeg -f concat -safe 0 -i ${temp.absolutePath} -c copy \"${output}\""
			if(opt.dryrun) {
				println "Concatenating:"
				print temp.text
				println "With:"
				println "${cmd}"
			} else {
				def buffer = new StringBuffer()
				def code = exitCodeFromCommand(sh(cmd), 0, buffer)
				if (code > 0) {
					System.err.println "Error: '${cmd}' returned exit code '${code}' while '0' was expected"
					System.exit(3) // return - keeping the temp file for debugging
				}
				print buffer.toString() // TODO report progress
			}
			temp.delete()
		}
		else if (opt.streams && !opt.output) {
			// Special case - just probe and list the streams...
			inputs.each { input ->
				if (inputs.size > 1)
					printName input
				def cmd = "ffprobe "
				// options are for some detailed output, but getting the error streams holds the relevant info
				cmd += "\"${input}\""
				if (opt.dryrun)
					println cmd
				def buffer = new StringBuffer()
				def code = exitCodeFromCommand(sh(cmd), 0, buffer)
				if (code > 0) {
					System.err.println "Error: '${cmd}' returned exit code '${code}' while '0' was expected"
					System.exit(5)
				}
				def list = parseStreams(buffer.toString(), true)
				println 'Suggested mapping:'
				list.each {
					println "${it.type.padRight(8, '.')}: ${it.index} ${it.desc ? '-- ' + it.desc : ''}"
				}
				println ''
			}
		} else {
			// ffmpeg needs the duration so we need to calculate that from begin and end
			def period = null
			if (opt.begin && opt.end) {
				period = fromSeconds(toSeconds(opt.end) - toSeconds(opt.begin))
			} else if (opt.end) {
				period = opt.end
			}
			def videoCodec = opt.transcode ? 'h264' : 'copy'
			inputs.each { input ->
				if (inputs.size > 1)
					printName input
				def output = outputPath(opt.output, input)
				def vMap = opt.video
				def aMap1 = opt.audio
				def aMap2 = null
				def sMap = opt.subtitle
				// if streams probe is requested but all streams are given already skip the probing
				if (opt.streams && !(vMap && aMap1 && sMap)) {
					def cmd = "ffprobe "
					// options are for some detailed output, but getting the error streams holds the relevant info
					cmd += "\"${input}\""
					def buffer = new StringBuffer()
					def code = exitCodeFromCommand(sh(cmd), 0, buffer)
					if (code > 0) {
						System.err.println "Error: '${cmd}' returned exit code '${code}' while '0' was expected"
						System.exit(6)
					}
					def list = parseStreams(buffer.toString(), false)
					// we'll consume max one video, two audio and one subtitle - if there are any further they are ignored
					list.each {
						switch (it.type) {
							case 'video':
								if (!vMap)
									vMap = it.index
								break
							case 'audio':
								if (!aMap1)
									aMap1 = it.index
								else if (!aMap2)
									aMap2 = it.index
								break
							case 'subtitle':
								if (!sMap)
									sMap = it.index
								break
						}
					}
				}
				// Set up main work (allowing to overwrite existing output)
				def cmd = "ffmpeg -y "
				if (opt.begin) cmd += "-ss ${opt.begin} "
				cmd += "-i \"${input}\" "
				if (vMap) cmd += "-map 0:${vMap} -vcodec ${videoCodec} "
				if (aMap1) cmd += "-map 0:${aMap1} -acodec copy "
				if (aMap2) cmd += "-map 0:${aMap2} -acodec copy "
				if (sMap) cmd += "-map 0:${sMap} -scodec copy "
				if (period) cmd += "-t ${period} "
				cmd += "\"${output}\""
		
				if (opt.dryrun) {
					println cmd
				} else {
					def buffer = new StringBuffer()
					def proc = sh(cmd).execute()
					proc.consumeProcessOutputStream(buffer) // No idea why ffmpeg outputs to stderr, so ignore stdout then
					InputStream stdout = proc.getErrorStream()
					BufferedReader reader = new BufferedReader(new InputStreamReader(stdout))
					def reg = ~/.*time=([0-9]{2}:[0-9]{2}:[0-9]{2})\.[0-9]{2}.*/
					def dur = ~/.*Duration: ([0-9]{2}:[0-9]{2}:[0-9]{2})\.[0-9]{2}.*/
					def total = period ? toSeconds(period) : null
					DecimalFormat df = new DecimalFormat("##0.0%");
					def line
					while ((line = reader.readLine()) != null) {
						def m = reg.matcher(line)
						if (m.matches()) {
							if (total) {
								def progress = toSeconds(m[0][1])
								def percent = df.format(progress / total)
								print "${percent.padLeft(6)} >> ${line}\r"
							} else {
								print line+'\r'
							}
						} else if (!total) {
							def m2 = dur.matcher(line)
							if (m2.matches()) {
								println "Run time: ${m2[0][1]}"
								total = toSeconds(m2[0][1])
							}
						}
					}
					println ''
		
					proc.waitFor()
					def code = proc.exitValue()
		
					try {
						reader.close();
					} catch (IOException ignoreMe) {
					}
		
					if (code > 0) {
						System.err.println "Error: '${cmd}' returned exit code '${code}' while '0' was expected"
						System.exit(7)
					}
				}
				println ''
			}
		}
		
		System.exit(0)
	}
	
	/**
	 * Based on given output and input this generates the actual output path
	 * 
	 * @param output
	 * @param input
	 * @return
	 */
	static String outputPath(String output, String input) {
		def root = ''
		
		if (output.startsWith('+')) {
			output = output[1..-1]
			root = FU.getFullPathNoEndSeparator(input)
		}
		def oPath = FU.getFullPath(output)
		def oName = FU.getBaseName(output)
		def oExt = FU.getExtension(output)
				
		if (oPath == output) {
			output = root + oPath + FU.getName(input) // just a folder
		} else if (!oExt) {
			output = root + output + '/' + FU.getName(input) // assume it is a folder without trailing slash
		} else if (!oName && oExt) {
			// extension with or without folder prefixed			
			output = root
			if(output && !output.endsWith('/') && !oPath.startsWith('/'))
				output += '/'
			output += oPath ?: (!root ? FU.getFullPath(input) : '')
			if(output && !output.endsWith('/'))
				output += '/'
			output += FU.getBaseName(input) + '.' + oExt
		}
		return output
	}
	
	/**
	 * Given output from ffprobe this will print (if verbose) all streams and return a map
	 * with best suggestions - this will be one h264 video stream, one or two audio streams
	 * (stereo and 5.1 audio streams) and one dvb_subtitle stream (though not ones marked for
	 * hearing impaired)
	 * <p>
	 * The maps returned have the following keys:<br>
	 * <b>type</b>:    (video|audio|subtitle)<br>
	 * <b>index</b>:   integer<br>
	 * <b>desc</b>: comment related to stream - here for user-friendliness
	 * <p>
	 * @param probeOutput String containing the "error" output from ffprobe
	 * @param verbose If true prints all streams found
	 * @return List of maps
	 */
	static parseStreams(String probeOutput, boolean verbose) {
	    def list = []
	    /* Seemingly no system when it comes to stream description, examples:
	    --- Kaffeine DVB-T (m2t):
	    Stream #0:0[0xd3]: Video: h264 (High) ([27][0][0][0] / 0x001B), yuv420p(tv, bt470bg), 704x576 [SAR 16:11 DAR 16:9], 25 fps, 50 tbr, 90k tbn, 50 tbc
	    Stream #0:1[0xdd](dan): Audio: aac_latm (HE-AAC) ([17][0][0][0] / 0x0011), 48000 Hz, stereo, fltp
	    Stream #0:2[0xe7](dan): Subtitle: dvb_teletext ([6][0][0][0] / 0x0006)
	    Stream #0:3[0xeb](dan): Subtitle: dvb_subtitle ([6][0][0][0] / 0x0006)
	    Stream #0:4[0xec](dan): Subtitle: dvb_subtitle ([6][0][0][0] / 0x0006) (hearing impaired)
	    --- Handbrake-ripped dvd (mkv)
	    Stream #0:0(eng): Video: h264 (High), yuv420p(tv, smpte170m/smpte170m/bt709), 706x300 [SAR 8:9 DAR 1412:675], SAR 127:143 DAR 44831:21450, 23.98 fps, 23.98 tbr, 1k tbn, 180k tbc (default)
	    Stream #0:1(eng): Audio: aac (LC), 48000 Hz, mono, fltp (default)
	    Stream #0:2(eng): Audio: ac3, 48000 Hz, mono, fltp, 192 kb/s
	    Stream #0:3(eng): Subtitle: subrip
	    --- Sony A5000 (MTS)
	    Stream #0:0[0x1011]: Video: h264 (High) (HDMV / 0x564D4448), yuv420p, 1920x1080 [SAR 1:1 DAR 16:9], 25 fps, 25 tbr, 90k tbn, 50 tbc
	    Stream #0:1[0x1100]: Audio: ac3 (AC-3 / 0x332D4341), 48000 Hz, stereo, fltp, 256 kb/s
	    Stream #0:2[0x1200]: Subtitle: hdmv_pgs_subtitle ([144][0][0][0] / 0x0090), 1920x1080
	    */
	    def streamPattern = ~/^\s*Stream #\d:(\d)(\[\w+\])?(\(.+\))?: (\w+): (.+)$/
	    int STREAM_IDX = 1
	    int OPT_CODE = 2
	    int AUDIO_LANG = 3
	    int STREAM_CAT = 4
	    int REMAINDER = 5
	    probeOutput.eachLine {
	        def m = streamPattern.matcher(it)
	        if(m.matches()) {
	            def map = [:]
	            if(verbose)
	                println it.trim()
	            switch (m[0][STREAM_CAT]) {
	                case 'Video':
	                    if(m[0][REMAINDER].startsWith('h264')) {
	                        map << [ 'type' : 'video' ]
	                        map << [ 'index' : m[0][STREAM_IDX] ]
	                        //map << [ 'desc' : m[0][REMAINDER] ]
	                        list << map
	                    }
	                    break;
	                case 'Audio':
	                    if( (!m[0][AUDIO_LANG] || m[0][AUDIO_LANG] == '(dan)' || m[0][AUDIO_LANG] == '(eng)')
	                            && (['aac','ac3','mp3'].find {m[0][REMAINDER].startsWith(it)})
	                            /*&& (m[0][REMAINDER].indexOf('stereo') || m[0][REMAINDER].indexOf('5.1'))*/ ) {
	                        map << [ 'type' : 'audio' ]
	                        map << [ 'index' : m[0][STREAM_IDX] ]
	                        map << [ 'desc' : m[0][REMAINDER] ]
	                        list << map
	                    }
	                    break;
	                case 'Subtitle':
	                    if( (m[0][REMAINDER].startsWith('dvb_subtitle') || m[0][REMAINDER].startsWith('subrip'))
	                            && m[0][REMAINDER].indexOf('hearing impaired') == -1){
	                        map << [ 'type' : 'subtitle' ]
	                        map << [ 'index' : m[0][STREAM_IDX] ]
	                        //map << [ 'desc' : m[0][REMAINDER] ]
	                        list << map
	                    }
	                    break;
	            }
	        }
	    }
	    return list
	}
	
	/**
	 * Generates list if input files to be concatenated, as well as the final path for the output file
	 *
	 * @param outputPath optional
	 * @param inputs as given on the command line
	 * @return Tuple with actual output file and list of file paths
	 */
	static concatList(String outputPath, List<String> inputs) {
		def root = ''
		def output = outputPath
		if(!output) {
			output = 'concat.' + FU.getExtension(inputs[0])
		} else if (FU.getBaseName(output) == output && !FU.getExtension(output)) {
			output = output + '.' + FU.getExtension(inputs[0])
		} else if (FU.getFullPath(output) == output) {
			output = root + FU.getFullPath(output) + 'concat.' + FU.getExtension(inputs[0]) // just a folder
		} else if (!FU.getExtension(output)) {
			output = root + output + '/' + 'concat.' + FU.getExtension(inputs[0]) // assume it is a folder without trailing slash
		}
		def list = []
		inputs.each {
			list << "file '${new File(it).absolutePath}'"
		}
		return new Tuple2(output, list)
	}
	
	//
	// Utils
	//

	static toSeconds(String format) {
		// Time format [[hh:][mm:]ss] eg. 01:05:27, or 05:00 for five minutes, or 30 for half a minute (01:90 and 2 minute and 30 seconds if so inclined)
	    def idx = 0;
	    format.split(':').reverse().collect({(it as int)*(60**idx++)}).sum()
	}
	
	static fromSeconds(int seconds) {
	    "${(seconds.intdiv(3600) as String).padLeft(2,'0')}:${((seconds % 3600).intdiv(60) as String).padLeft(2,'0')}:${((seconds % 60) as String).padLeft(2,'0')}"
	}
	
	static printName(String name) {
	    println '='*name.size()
	    println name
	    println '='*name.size()
	}

	/**
	 * Wraps the wait for stuff
	 * @param cmd String or List
	 * @param millis period - zero waits until process ends
	 * @param errBuffer Stderr output
	 * @return exit code - if killed process seems to return 143 - which in turn appears to be 128 + 15 (latter being the signal value)
	 */
	static int exitCodeFromCommand(def cmd, int millis, StringBuffer errBuffer) {
		def proc = cmd.execute()
		if(millis>0)
			proc.waitForOrKill(millis)
		else
			proc.waitFor()
		if(errBuffer != null)
			errBuffer.append(proc.err.text)
		return proc.exitValue()
	}
	
	
	/**
	 * To allow for shell expansion of wildcards it is necessary to run via a shell (and not directly from javas execution)
	 * @param cmd
	 * @return List with shell command
	 */
	static List sh(String cmd) {
		['sh', '-c', cmd]
	}
}

