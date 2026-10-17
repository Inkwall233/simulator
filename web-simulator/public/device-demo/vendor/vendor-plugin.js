/**
 * 厂商媒体解码插件（设备自带）
 *
 * 真实设备页依赖本地安装的浏览器插件来解码 H.264 流：
 * 插件通过 NPAPI/PPAPI 或本地软解码器把裸流渲染到页面上。
 * 本演示环境不具备真实插件，因此该插件只做「占位」：
 * 挂一个 <video> 指向录像文件，并提示解码器缺失。
 *
 * 仿真模拟器会在「插件劫持」模式下把整个 window.DeviceVendorPlugin
 * 替换为基于 canvas 的图片帧播放器。
 */
(function () {
  'use strict'

  window.DeviceVendorPlugin = {
    id: 'acme-mediadec',
    name: 'ACME Media Decoder',
    version: '1.0.3',
    kind: 'native-plugin',
    // 标记：这是「原始」插件，未经仿真加工
    hijacked: false,

    /**
     * @param {HTMLElement} host  播放容器
     * @param {object} recording  录像元数据
     */
    create: function (host, recording) {
      host.innerHTML = ''

      var wrap = document.createElement('div')
      wrap.className = 'plugin-original'
      wrap.style.cssText = 'width:100%;height:100%;position:relative;background:#0b1016;'

      var video = document.createElement('video')
      video.controls = true
      video.preload = 'none'
      video.style.cssText = 'width:100%;height:300px;object-fit:contain;background:#000;'
      video.src = recording.url
      video.addEventListener('error', function () {
        note.hidden = false
      })
      wrap.appendChild(video)

      var note = document.createElement('div')
      note.className = 'plugin-note'
      note.hidden = true
      note.style.cssText =
        'position:absolute;inset:0;display:flex;align-items:center;justify-content:center;' +
        'color:#c9d4e0;background:rgba(6,10,14,.82);font-size:13px;text-align:center;padding:16px;'
      note.textContent = '⚠ 厂商解码插件未加载，无法解析 .h264 裸流（真实设备需安装本地插件）'
      wrap.appendChild(note)

      var title = document.createElement('div')
      title.style.cssText =
        'position:absolute;left:10px;bottom:8px;color:#9fb0c0;font-size:12px;'
      title.textContent = recording.name + ' · ' + recording.codec
      wrap.appendChild(title)

      host.appendChild(wrap)

      return {
        pluginId: 'acme-mediadec',
        destroy: function () {
          host.innerHTML = ''
        }
      }
    }
  }
})()
