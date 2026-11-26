#!/usr/bin/env node
// A Household's self-hosted Stremio add-on, built on the official stremio-addon-sdk (1.6.10), that the
// household-*.json recordings were captured from. No public add-on offers http(s) Streams of freely licensed
// films any more, and none carries Celastrina's quality extension under behaviorHints, so this one does both.
//
// Re-record: npm install stremio-addon-sdk@1.6.10 && node household-addon.js, then curl each route on port 43001.
const { addonBuilder, serveHTTP } = require('stremio-addon-sdk')

const builder = new addonBuilder({
	id: 'org.household.archive',
	version: '1.0.0',
	name: 'Household Archive',
	description: 'Films from the household NAS and the Internet Archive',
	resources: ['catalog', 'meta', 'stream'],
	types: ['movie', 'series'],
	idPrefixes: ['tt'],
	catalogs: [{ type: 'movie', id: 'household', name: 'Household Archive' }],
})

const ia = 'https://archive.org/download/Night.Of.The.Living.Dead_1080p'

const streams = {
	'movie:tt0063350': [
		{
			name: 'Household Archive\n720p',
			description: 'Night of the Living Dead (1968)\nMKV · 10.7 GB',
			url: `${ia}/NightOfTheLivingDead_1080p.mkv`,
			behaviorHints: {
				bingeGroup: 'household-1080p',
				videoSize: 11523767255,
				filename: 'NightOfTheLivingDead_1080p.mkv',
				celastrina: {
					id: 'notld-1080p-mkv',
					resolution: 1080,
					videoCodec: 'h264',
					container: 'mkv',
					audioLanguages: ['eng'],
					bitrate: 16024000,
					size: 11523767255,
				},
			},
		},
		{
			name: 'Household Archive 720p',
			description: 'Night of the Living Dead (1968)\nMP4',
			url: `${ia}/NightOfTheLivingDead_720p.mp4`,
			subtitles: [{ id: 'notld-eng', url: `${ia}/NightOfTheLivingDead_DVD5.asr.srt`, lang: 'eng' }],
			behaviorHints: { videoSize: 597796730 },
		},
		{
			name: 'Household NAS\nAuto',
			description: 'Night of the Living Dead (1968)\nAdaptive',
			url: 'http://192.168.1.20:8080/hls/night-of-the-living-dead/master.m3u8',
			behaviorHints: { notWebReady: true, proxyHeaders: { request: { Referer: 'http://192.168.1.20:8080/' } } },
		},
		{ name: '1080p', title: '💾 1.51 GB', infoHash: '11ea02584fa6351956f35671962ab46354d99060', fileIdx: 0 },
		{ name: 'Trailer', ytId: 'H91BxkBXttE' },
		{ name: 'Usenet 1080p', nzbUrl: 'https://indexer.example/getnzb/notld.nzb', servers: ['nntps://news.example:563/4'] },
		{ name: 'Free with ads', externalUrl: 'https://archive.org/details/Night.Of.The.Living.Dead_1080p' },
		{
			name: 'Studio 4K',
			url: 'https://cdn.example/notld/manifest.mpd',
			behaviorHints: { celastrina: { id: 'studio-4k', resolution: 2160, delivery: 'dash', drm: true } },
		},
	],
	'series:tt1748166:1:1': [
		{
			name: 'Household Archive 360p',
			description: 'Pioneer One S01E01 Earthfall',
			url: 'https://archive.org/download/PioneerOneS01E01720pHD/Content/Pioneer.One.S01E01.REFIX.720p.x264-VODO.mp4',
		},
	],
	'series:tt1748166:1:2': [
		{ url: 'https://nas.example/season.1/episode?next=manifest.mpd' },
		{ url: 'https://nas.example/pioneer/manifest.MPD?token=example' },
		{ url: 'https://nas.example/pioneer/playlist', behaviorHints: { celastrina: { delivery: 'hls' } } },
	],
}

builder.defineStreamHandler(({ type, id }) => {
	if (id === 'tt0111161') return Promise.reject(new Error('the NAS is asleep'))
	return Promise.resolve({ streams: streams[`${type}:${id}`] || [] })
})

builder.defineCatalogHandler(() => Promise.resolve({ metas: [] }))
builder.defineMetaHandler(() => Promise.resolve({ meta: null }))

serveHTTP(builder.getInterface(), { port: process.env.PORT || 43001 })
