@Grapes(
    @Grab(group='org.spockframework', module='spock-core', version='1.1-groovy-2.4', scope='test')
)

import spock.lang.*
import Recoder

class RecoderSpec extends Specification {
	
	def "probe Kaffeine DVB-T recording"() {
		
		String probeData = '''Stream #0:0[0xd3]: Video: h264 (High) ([27][0][0][0] / 0x001B), yuv420p(tv, bt470bg), 704x576 [SAR 16:11 DAR 16:9], 25 fps, 50 tbr, 90k tbn, 50 tbc
Stream #0:1[0xdd](dan): Audio: aac_latm (HE-AAC) ([17][0][0][0] / 0x0011), 48000 Hz, stereo, fltp
Stream #0:2[0xe7](dan): Subtitle: dvb_teletext ([6][0][0][0] / 0x0006)
Stream #0:3[0xeb](dan): Subtitle: dvb_subtitle ([6][0][0][0] / 0x0006)
Stream #0:4[0xec](dan): Subtitle: dvb_subtitle ([6][0][0][0] / 0x0006) (hearing impaired)'''
			
		expect:
			Recoder.parseStreams(probeData, false) == [
				[type: 'video', index: '0'], 
				[type: 'audio', index: '1', desc: 'aac_latm (HE-AAC) ([17][0][0][0] / 0x0011), 48000 Hz, stereo, fltp'],
				[type: 'subtitle', index: '3']]
	}
	
	def "probe handbrake-ripped dvd"() {
		
		String probeData = '''Stream #0:0(eng): Video: h264 (High), yuv420p(tv, smpte170m/smpte170m/bt709), 706x300 [SAR 8:9 DAR 1412:675], SAR 127:143 DAR 44831:21450, 23.98 fps, 23.98 tbr, 1k tbn, 180k tbc (default)
Stream #0:1(eng): Audio: aac (LC), 48000 Hz, mono, fltp (default)
Stream #0:2(eng): Audio: ac3, 48000 Hz, mono, fltp, 192 kb/s
Stream #0:3(eng): Subtitle: subrip'''
			
		expect:
			Recoder.parseStreams(probeData, false) == [
				[type: 'video', index: '0'],
				[type: 'audio', index: '1', desc: 'aac (LC), 48000 Hz, mono, fltp (default)'],
				[type: 'audio', index: '2', desc: 'ac3, 48000 Hz, mono, fltp, 192 kb/s'],
				[type: 'subtitle', index: '3']]
			
	}
	
	def "probe Sony A5000 recording"() {

		String probeData = '''Stream #0:0[0x1011]: Video: h264 (High) (HDMV / 0x564D4448), yuv420p(top first), 1920x1080 [SAR 1:1 DAR 16:9], 25 fps, 25 tbr, 90k tbn, 50 tbc
Stream #0:1[0x1100]: Audio: ac3 (AC-3 / 0x332D4341), 48000 Hz, stereo, fltp, 256 kb/s
Stream #0:2[0x1200]: Subtitle: hdmv_pgs_subtitle ([144][0][0][0] / 0x0090), 1920x1080'''
		
		expect:
			Recoder.parseStreams(probeData, false) == [
				[type:'video', index:'0'], 
				[type:'audio', index:'1', desc: 'ac3 (AC-3 / 0x332D4341), 48000 Hz, stereo, fltp, 256 kb/s']]          
	}

	def "concat list with null output"() {
		
		def inputs = ['/tmp/foo.mkv', '/tmp/bar.mkv']
		def outputPath = null
		
		when:
		def (output, list) = Recoder.concatList(outputPath, inputs)
		
		then:
		output == 'concat.mkv'
		list == ["file '/tmp/foo.mkv'", "file '/tmp/bar.mkv'"]
	}

	def "concat list with given output"() {

		def inputs = ['/tmp/foo.mkv', '/tmp/bar.mkv']
		def outputPath = 'combined.mkv'

		when:
		def (output, list) = Recoder.concatList(outputPath, inputs)

		then:
		output == 'combined.mkv'
		list == ["file '/tmp/foo.mkv'", "file '/tmp/bar.mkv'"]
	}

	def "convert time format to seconds"() {
		expect:
		Recoder.toSeconds(time) == seconds
		
		where:
		time		|| seconds
		'01:05:27'	|| 3600+300+27
		'05:00'		|| 300
		'27'		|| 27
		'01:90'		|| 60+90
	}
	
	def "convert from time format"() {
		expect:
		Recoder.fromSeconds(seconds) == time
		
		where:
		seconds		|| time
		3600+300+27	|| '01:05:27'
		300			|| '00:05:00'
		27			|| '00:00:27'
		60+90		|| '00:02:30'
	}
	
	@Unroll
	def "output path from arg: '#output' and input: '#input'"() {
		expect:
		Recoder.outputPath(output,input) == path
		
		where:
		output		| input				|| path
		'/'			| 'foo.m2t'			|| '/foo.m2t'
		'/tmp'		| 'foo.m2t'			|| '/tmp/foo.m2t'
		'/tmp/'		| 'foo.m2t'			|| '/tmp/foo.m2t'
		'/tmp/'		| '/film/foo.m2t'	|| '/tmp/foo.m2t'
		'.mkv'		| 'foo.m2t'			|| 'foo.mkv'
		'.mkv'		| '/film/foo.m2t'	|| '/film/foo.mkv'
		'/tmp/.mkv'	| 'foo.m2t'			|| '/tmp/foo.mkv'
		'/tmp/.mkv'	| '/film/foo.m2t'	|| '/tmp/foo.mkv'
		'+.mkv'		| 'foo.m2t'			|| 'foo.mkv'
		'+.mkv'		| '/film/foo.m2t'	|| '/film/foo.mkv'
	}
	
}


