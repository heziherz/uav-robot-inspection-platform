#!/usr/env python3
# -*- coding: utf-8 -*-
"""Insert Chapter 4 content into the course design report docx."""

import copy
from docx import Document
from docx.shared import Inches, Pt, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

DOC_PATH = r"D:\school\all\项目\企业项目实践\doc\企业应用开发实践课程设计报告.docx"
SAVE_PATH = r"D:\school\all\项目\企业项目实践\doc\企业应用开发实践课程设计报告_第4章完成.docx"
IMG_DIR = r"D:\school\all\项目\企业项目实践\doc\images\架构分析"

doc = Document(DOC_PATH)

# ---- Find Chapter 4 and Chapter 5 paragraph indices ----
ch4_idx = None
ch5_idx = None
for i, p in enumerate(doc.paragraphs):
    text = p.text.strip()
    if text.startswith("第 4 章") or text.startswith("第4章"):
        ch4_idx = i
    if text.startswith("第 5 章") or text.startswith("第5章"):
        ch5_idx = i
        break

if ch4_idx is None:
    print("ERROR: Cannot find '第 4 章' in document")
    exit(1)
if ch5_idx is None:
    print("ERROR: Cannot find '第 5 章' in document")
    exit(1)

print(f"Chapter 4 at paragraph index {ch4_idx}, Chapter 5 at {ch5_idx}")

# ---- Delete all paragraphs from ch4_idx to ch5_idx (exclusive) ----
# We keep ch4_idx paragraph (the chapter title) and delete the rest
# Actually, we delete everything from ch4_idx to ch5_idx-1, then insert new content

# First, collect the XML elements to remove
elements_to_remove = []
for i in range(ch4_idx, ch5_idx):
    p = doc.paragraphs[i]
    elements_to_remove.append(p._element)

# Remove them from the XML tree
for elem in elements_to_remove:
    elem.getparent().remove(elem)

print(f"Removed {len(elements_to_remove)} template paragraphs")

# ---- Now insert Chapter 4 content after the previous paragraph (ch4_idx-1) ----
# We need to insert at the position where ch4_idx was
# After deletion, the paragraph that was ch5_idx is now at ch4_idx
# So we insert before it

insert_before = doc.paragraphs[ch4_idx]._element

# ---- Helper functions ----
def make_heading(doc, text, level):
    """Create a heading paragraph with manual formatting and insert before insert_before."""
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.bold = True
    if level == 1:
        run.font.size = Pt(18)
    elif level == 2:
        run.font.size = Pt(16)
    elif level == 3:
        run.font.size = Pt(14)
    else:
        run.font.size = Pt(12)
    run.font.name = 'SimHei'
    run._element.rPr.rFonts.set(qn('w:eastAsia'), 'SimHei')
    # Set outline level for document structure
    from docx.oxml import OxmlElement
    pPr = p._element.get_or_add_pPr()
    outlineLvl = OxmlElement('w:outlineLvl')
    outlineLvl.set(qn('w:val'), str(level - 1))
    pPr.append(outlineLvl)
    # Add spacing
    p.paragraph_format.space_before = Pt(12)
    p.paragraph_format.space_after = Pt(6)
    insert_before.addprevious(p._element)
    return p

def make_para(doc, text, bold=False, font_size=None):
    """Create a normal paragraph."""
    p = doc.add_paragraph()
    run = p.add_run(text)
    if bold:
        run.bold = True
    if font_size:
        run.font.size = Pt(font_size)
    # Set Chinese font
    run.font.name = 'SimSun'
    run._element.rPr.rFonts.set(qn('w:eastAsia'), 'SimSun')
    insert_before.addprevious(p._element)
    return p

def make_image(doc, img_path, width=5.5):
    """Insert an image."""
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    run.add_picture(img_path, width=Inches(width))
    insert_before.addprevious(p._element)
    return p

def _set_table_borders(table):
    """Add single-line borders to all cells in a table via XML."""
    from docx.oxml import OxmlElement
    tbl = table._tbl
    tblPr = tbl.tblPr
    # Remove existing borders element if any
    for elem in tblPr.findall(qn('w:tblBorders')):
        tblPr.remove(elem)
    tblBorders = OxmlElement('w:tblBorders')
    for border_name in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'):
        border = OxmlElement(f'w:{border_name}')
        border.set(qn('w:val'), 'single')
        border.set(qn('w:sz'), '4')
        border.set(qn('w:space'), '0')
        border.set(qn('w:color'), '000000')
        tblBorders.append(border)
    tblPr.append(tblBorders)

def make_table(doc, data, has_header=True):
    """Create a table from 2D array."""
    rows = len(data)
    cols = len(data[0]) if rows > 0 else 0
    table = doc.add_table(rows=rows, cols=cols)
    _set_table_borders(table)
    for i, row_data in enumerate(data):
        for j, cell_text in enumerate(row_data):
            cell = table.cell(i, j)
            cell.text = str(cell_text)
            if has_header and i == 0:
                for p in cell.paragraphs:
                    for r in p.runs:
                        r.bold = True
    insert_before.addprevious(table._element)
    # Add empty paragraph after table
    p = doc.add_paragraph()
    insert_before.addprevious(p._element)
    return table

def make_caption(doc, text):
    """Create a centered caption paragraph."""
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    run.font.size = Pt(10)
    run.font.name = 'SimSun'
    run._element.rPr.rFonts.set(qn('w:eastAsia'), 'SimSun')
    insert_before.addprevious(p._element)
    return p

# ---- Now write all Chapter 4 content ----
import os

# 4 章标题
make_heading(doc, '第4章 系统总体设计', 1)

# 4.1
make_heading(doc, '4.1 系统设计原则', 2)
make_para(doc, '本系统面向园区安防空地协同巡检场景，在设计过程中遵循以下五项原则，确保架构层次清晰、职责明确、可扩展、可教学：')
make_para(doc, '（1）分层解耦原则。设备仿真、消息中间件、业务服务、分布式存储、检索分析、网关接入、可视化展示各层独立，层间以消息协议或 REST 接口交互，降低模块间耦合度，便于独立开发与替换。')
make_para(doc, '（2）数据驱动原则。以"数据上报→消息流转→分布式存储/检索→可视化展示"为主线设计系统，所有模块围绕数据生命周期展开，确保数据链路完整可追溯。')
make_para(doc, '（3）组件即课程原则。架构须完整覆盖课程强制要求的六类组件——HDFS、MongoDB/HBase、Kafka/RocketMQ、Nginx、Elasticsearch、Docker，每一类组件在系统中承担明确职责，体现企业应用集成的学习目标。')
make_para(doc, '（4）面向扩展原则。Kafka topic 按业务划分，存储读写接口化，新增设备类型或业务模块时只需新增 topic 与消费逻辑，不修改已有链路；业务服务采用模块化单体架构，内部按业务领域划分子模块，为后续微服务化预留边界。')
make_para(doc, '（5）仿真优先、真实贴近原则。设备以软件仿真模块模拟，但数据格式、消息协议、接口语义均贴近真实系统，确保实验结论具有工程参考价值。')

# 4.2
make_heading(doc, '4.2 系统总体架构设计', 2)
make_para(doc, '本系统采用七层分层架构，各层之间通过消息协议或 HTTP 接口交互，依赖方向单向无环。整体架构如图4-1所示。')

# Insert architecture image
img_path = os.path.join(IMG_DIR, "分层架构图.png")
if os.path.exists(img_path):
    make_image(doc, img_path, 5.5)
    make_caption(doc, "图4-1 系统分层架构图")
else:
    make_para(doc, "[图4-1 系统分层架构图]")

make_para(doc, '图4-1中各层职责与技术要素如表4-1所示。')

t41_data = [
    ["层次", "职责", "承载技术组件", "关键数据"],
    ["设备仿真层", "模拟无人机/机器狗注册、心跳、传感器与影像元数据产生；接收并执行任务指令", "无人机/机器狗仿真模块（自研，Spring Boot）", "GPS、传感值、图片元数据、状态上报、任务回执"],
    ["消息中间件层", "接收设备上报消息、下发平台指令；消息生产/消费/持久化/分区路由", "Apache Kafka 4.3.1（KRaft模式）", "上报消息（6类topic）、指令消息（1类topic）"],
    ["业务服务层", "设备台账维护、任务解析与下发、告警处理、消息消费、业务逻辑执行", "Java 17 + Spring Boot 4 + Spring Kafka", "结构化业务数据、指令消息、REST响应"],
    ["分布式存储层", "巡检影像大文件存储；结构化业务数据存储读写", "Hadoop HDFS 3.2.1 + MongoDB 8.3.9", "影像文件、设备/任务/告警/传感器记录"],
    ["检索分析层", "告警事件索引建立、全文检索、聚合统计", "Elasticsearch 9.5.3 + Kibana", "索引文档、统计聚合结果"],
    ["网关接入层", "统一对外入口、反向代理、负载均衡、前端静态托管", "Nginx 1.30", "HTTP请求转发"],
    ["可视化展示层", "设备GIS分布、实时态势、任务进度、告警列表、统计分析图表", "React 19 + Vite + Ant Design 6 + ECharts + Leaflet", "业务JSON、地图标记、图表数据"],
]
make_table(doc, t41_data)
make_caption(doc, '表4-1 各层职责与技术要素')

make_para(doc, '需要特别说明的是，图中存在业务闭环但无循环依赖。具体而言，"上报链"（设备仿真→Kafka→业务服务）与"下发链"（业务服务→Kafka→设备仿真）在业务上构成闭环，但在代码依赖上，设备仿真与业务服务各自仅持有 Kafka 客户端，互不持有对方接口，因此不构成循环依赖。消息中间件在此的作用正是将"上报—下发"这个环从直接调用中切开，实现生产者与消费者在时空上的解耦。')

# 4.3
make_heading(doc, '4.3 技术选型说明', 2)
make_para(doc, '本系统技术选型遵循"课程覆盖优先、生态一致性优先、部署简洁优先"三条准则。各技术域的最终选定方案与选型依据如表4-2所示。')

t42_data = [
    ["技术域", "最终选定", "备选方案", "选型要点"],
    ["容器化部署", "Docker", "裸机/虚拟机", "镜像隔离、一键部署、固定版本规避冲突、单机模拟集群"],
    ["分布式文件系统", "Hadoop HDFS 3.2.1", "MinIO/FastDFS", "课程强制要求；大文件写后读场景契合HDFS定位；与大数据生态衔接"],
    ["分布式数据库", "MongoDB 8.3.9", "HBase", "文档模型灵活、查询语义直观、部署轻量；数据量未达海量级时开发更快"],
    ["消息中间件", "Apache Kafka 4.3.1", "RocketMQ", "高吞吐流式数据场景契合；分区内有序、持久化成熟；与HDFS/ES同属Apache生态"],
    ["检索引擎", "Elasticsearch 9.5.3 + Kibana", "—", "全文检索+聚合+可视化一体；支持地理检索对应GIS场景"],
    ["负载均衡网关", "Nginx 1.30", "—", "反向代理与负载均衡的事实标准；配置简单、高并发成熟可靠"],
    ["后端开发", "Java 17 + Spring Boot 4", "Python/FastAPI", "与中间件同生态；Spring生态一体化；强类型利于多人协作"],
    ["前端框架", "React 19 + Vite + Ant Design 6", "纯HTML+JS", "生态最大、企业级组件库成熟、图表与地图能力完整"],
]
make_table(doc, t42_data)
make_caption(doc, '表4-2 技术选型总览表')

make_para(doc, '在数据库选型方面，MongoDB与HBase的核心差异在于数据模型与查询能力。MongoDB采用BSON文档模型，字段灵活，支持按字段、条件、范围和聚合查询，语义直观，适合业务频繁调整的开发阶段；HBase采用宽表列族模型，需预先设计schema，以主键和范围扫描为主，复杂查询能力较弱，更适合超大规模数据场景。本项目结构化数据体量适中，需求以"按设备编号、按任务编号、按时间查询"为主，MongoDB的文档模型与查询能力更贴合且开发更快，故选MongoDB。HBase保留为备选，若后续数据量急剧增长可平滑替换，上层读写接口可抽象屏蔽差异。')
make_para(doc, '在消息中间件选型方面，Kafka与RocketMQ均可达成需求。本项目推荐Kafka的原因是：设备高频心跳与巡检数据上报是典型高吞吐流式数据场景，与Kafka的分布式日志设计目标契合；Kafka消息持久化、分区内有序能很好支撑"上报不丢失、指令按序"的需求；Kafka与HDFS、Elasticsearch同属Apache生态，集成资料丰富。RocketMQ保留为备选，两者生产者/消费者模型一致，替换时架构与业务代码不受影响。')
make_para(doc, '整体技术栈呈Apache/Java/大数据生态一致性：Kafka、HDFS、Elasticsearch均为Java实现且有官方Java客户端，后端采用Java+Spring Boot可实现从设备上报、消息消费、存储写入到检索查询的一条Java技术链，大幅降低跨语言集成成本与排错难度。所有中间件统一以Docker镜像交付并固定版本，规避多组件版本兼容风险。')

# 4.4
make_heading(doc, '4.4 模块划分', 2)
make_para(doc, '本系统按【参与者+用例聚类+技术支撑】三个维度划分模块。遵循两条原则：第一，模块是逻辑单位而非物理单位，一个模块可对应独立工程也可对应工程内的若干包；第二，按"变化与伸缩的原因"划分而非按技术种类划分，避免每次业务变更都需同时修改多个模块。据此，系统划分为7个功能模块（M1~M7）与4个技术支撑模块（T1~T4），下文按物理部署单元逐节说明。')

# 4.4.1
make_heading(doc, '4.4.1 设备仿真模块', 3)
make_para(doc, '设备仿真模块对应功能模块M1的仿真侧，物理实现为独立工程backend/device-simulator，以Spring Boot应用形式在宿主机或容器中独立运行。该模块代表系统外部参与者——无人机（A4）与机器狗（A5），模拟设备注册接入、周期心跳上报、GPS轨迹生成、环境传感器读数、航拍影像元数据上报与告警触发，同时订阅平台下发的巡检任务指令并回执执行结果。')
make_para(doc, '仿真模块包含3台无人机与3台机器狗（默认配置，可通过参数扩展至500台）。每台设备按照真实巡检语义产生数据：无人机每5秒上报心跳、每2秒上报GPS位置、每10秒上报航拍影像元数据；机器狗每5秒上报心跳、每3秒上报位置与环境传感器读数、每12秒上报红外影像元数据。所有数据经Kafka生产者发送至对应topic，与平台之间通过消息中间件解耦，不直连业务数据库。')
make_para(doc, '该模块必须独立进程运行的原因是：它代表系统外部设备（参与者A4/A5），若与平台同一进程，则"设备经消息中间件接入平台"的架构设定不成立。仿真端的线程池采用全局共享设计（默认线程数=CPU核数，最少4），不随设备数线性增长，避免了改造前"每设备一个线程池"在大规模设备下线程爆炸的问题。')

# 4.4.2
make_heading(doc, '4.4.2 消息中间件接入模块', 3)
make_para(doc, '消息中间件接入模块对应技术支撑模块T1，由Kafka容器与平台侧的消息收发代码共同构成。Kafka采用KRaft模式（免Zookeeper），单节点Broker+Controller，通过双listener解决容器内外寻址问题：容器内服务使用kafka:9092通信，宿主机进程使用localhost:19092通信。')
make_para(doc, '系统共规划7个Kafka topic，按业务方向分为上行6个（设备→平台）与下行1个（平台→设备），如表4-3所示。')

t43_data = [
    ["Topic名称", "方向", "消息内容", "生产者", "消费者"],
    ["topic_device_heartbeat", "上行", "心跳（设备编号、状态、电量、位置）", "仿真设备", "平台心跳消费者"],
    ["topic_device_gps", "上行", "GPS轨迹（设备编号、经纬度、时间戳）", "仿真设备", "平台遥测消费者"],
    ["topic_device_sensor", "上行", "环境传感器读数（温度、湿度、气体、设备温度）", "仿真设备", "平台传感器消费者"],
    ["topic_device_media_meta", "上行", "影像元数据（文件ID、设备编号、类型、HDFS路径）", "仿真设备", "平台影像消费者"],
    ["topic_device_alarm", "上行", "告警事件（设备编号、类型、级别、位置、描述）", "仿真设备", "平台告警消费者"],
    ["topic_device_task_ack", "上行", "任务回执（任务ID、设备编号、执行状态、结果）", "仿真设备", "平台任务回执消费者"],
    ["topic_platform_command", "下行", "巡检任务指令（任务ID、设备编号、任务类型、目标区域）", "平台任务服务", "仿真设备"],
]
make_table(doc, t43_data)
make_caption(doc, '表4-3 Kafka Topic规划表')

make_para(doc, '平台侧消息消费基于Spring Kafka的@KafkaListener注解实现，6个上行topic各对应一个独立消费者。消费并发度（spring.kafka.listener.concurrency）设置为3，与topic分区数一致，使每个topic的3个分区各有一个消费者线程并行处理。消息消费后，业务服务将结构化数据写入MongoDB，影像文件由设备直接上传至HDFS，告警事件同时双写Elasticsearch。')
make_para(doc, '下行指令由平台的MessagePublisher组件发送至topic_platform_command，仿真设备订阅该topic接收任务指令。任务回执经topic_device_task_ack返回平台，形成"下发→执行→回执"的完整闭环。消息中间件在此实现了生产者与消费者在时空上的解耦，支持异步处理与削峰填谷。')

# 4.4.3
make_heading(doc, '4.4.3 业务服务模块', 3)
make_para(doc, '业务服务模块对应功能模块M1~M5的平台侧部分，物理实现为独立工程backend/platform-service，以Spring Boot应用形式部署为Docker容器（2个实例，由Nginx负载均衡）。该模块是系统的核心业务处理单元，内部按业务领域划分为设备接入、巡检任务、告警中心、检索统计、台账权限五个子模块，通过包结构区分职责。')
make_para(doc, '业务服务的内部包结构遵循DDD分层：controller包作为入站适配器处理HTTP请求；consumer包作为消息入站适配器消费Kafka消息；service包承载业务领域逻辑；repository包作为出站适配器操作MongoDB；common/MessagePublisher负责消息出站；service/HdfsService负责文件出站。config包声明Topics常量、AuthInterceptor认证拦截器和WebConfig跨域配置。')
make_para(doc, '业务服务采用模块化单体（Modular Monolith）架构而非微服务，工程依据是：第一，各业务模块的伸缩特征、变化速率和可靠性诉求尚未分化到需要独立部署的程度；第二，容量压测实验表明系统实际瓶颈位于Kafka分区数而非业务服务计算能力，当前瓶颈未消除前拆分服务不能提升吞吐；第三，业界主流实践主张先确立正确的模块边界再依据实际压力逐步物理拆分。该架构的微服务演进路径已纳入第7章后续改进与展望。')
make_para(doc, '业务服务部署2个实例的原因是：演示Nginx负载均衡能力，同时验证"无状态服务可水平扩展"——认证采用JWT不存session，内存缓冲区各实例独立，数据库/ES/HDFS客户端各自连接。两个实例均不映射8080端口到宿主机，仅在容器网络内通过Nginx反向代理对外提供服务。')

# 4.4.4
make_heading(doc, '4.4.4 分布式存储模块', 3)
make_para(doc, '分布式存储模块对应技术支撑模块T2，由HDFS容器、MongoDB容器与平台侧的数据访问代码共同构成。采用"两库分离"策略：HDFS存储巡检影像大文件（MB级图片），MongoDB存储结构化业务数据（设备状态、任务台账、告警记录、传感器读数、影像元数据）。')
make_para(doc, 'HDFS采用伪分布式部署，拆分NameNode与DataNode两个容器。副本数设为1（单机演示场景），关闭权限校验便于开发。影像文件由设备仿真端直接通过WebHDFS接口上传至HDFS，消息中只传存储引用（文件ID与HDFS路径），平台侧通过WebHDFS读取影像用于前端预览与下载。这种"设备直传存储"的设计避免了MB级大文件挤占Kafka消息通道，实现了消息通道与文件通道的分离。')
make_para(doc, 'MongoDB采用单节点部署，WiredTiger存储引擎缓存设为512MB。数据库uav_platform包含6类集合：device_status（设备状态，普通集合）、task（任务台账，普通集合）、alarm（告警记录，普通集合）、media_meta（影像元数据，普通集合）、telemetry（巡检轨迹GPS，时序集合）、sensor_data（传感器读数，时序集合）、task_log（任务执行回执流水，时序集合）。时序集合按设备编号分组、按事件时间索引，并设置7天自动过期，兼顾高吞吐写入与存储空间控制。')
make_para(doc, '数据访问层基于Spring Data MongoDB的Repository接口实现，上层业务逻辑通过接口调用而非直接操作数据库，便于后续替换存储实现。初始化脚本mongo-init.js在容器首次启动时自动创建时序集合与辅助索引，设计为可重复执行（幂等）。')

# 4.4.5
make_heading(doc, '4.4.5 检索分析模块', 3)
make_para(doc, '检索分析模块对应技术支撑模块T3，由Elasticsearch容器、Kibana容器与平台侧的索引/检索代码共同构成。Elasticsearch采用单节点部署，关闭xpack安全认证便于演示，JVM堆内存配置为512MB。')
make_para(doc, 'Elasticsearch在系统中承担两项职责：第一，告警事件索引存储——平台消费告警消息后，将告警文档同时写入MongoDB（主存储）与Elasticsearch（检索索引），实现"双写"策略，MongoDB负责事务性读写与状态更新，Elasticsearch负责全文检索与聚合分析；第二，统计分析数据源——前端统计分析页面的KPI卡片与ECharts图表通过Elasticsearch聚合查询获取告警趋势、级别分布等统计数据。')
make_para(doc, '告警索引（alarm）的文档结构包含告警ID（主键，幂等写入）、设备编号、告警类型、级别、经纬度、媒体文件ID、描述、事件时间、平台接收时间、处置状态、处置人和处置备注等字段。经纬度字段支持后续扩展为geo_point类型以支持地理检索。')
make_para(doc, 'Kibana作为运维分析的旁路工具，通过内网直连Elasticsearch（不经过Nginx业务网关），提供告警仪表盘、索引浏览和Dev Tools等运维能力。Kibana不属于Web访问链路，定位为运维人员（A3）的辅助工具。')

# 4.4.6
make_heading(doc, '4.4.6 Nginx 网关模块', 3)
make_para(doc, 'Nginx网关模块对应技术支撑模块T4，物理实现为nginx容器（nginx:1.30-alpine）加配置文件deploy/nginx/conf.d/default.conf。该模块是系统唯一的对外入口，承担两项职责：前端静态资源托管与后端API反向代理。')
make_para(doc, '前端静态资源托管通过root指令指向React构建产物目录（/usr/share/nginx/html），使用try_files实现SPA路由回退（找不到的路径返回index.html），支持前端React Router的客户端路由。')
make_para(doc, '后端API反向代理通过upstream块定义后端服务集群（platform-service-1:8080与platform-service-2:8080），location /api/块将所有API请求proxy_pass至该集群，默认轮询策略将请求依次分发到两个实例。代理过程中设置X-Real-IP、X-Forwarded-For等请求头传递客户端真实信息，并通过add_header X-Upstream $upstream_addr响应头回传实际处理请求的后端实例地址，用于验证负载均衡是否生效。')
make_para(doc, '需要说明的是，Nginx开源版在启动时解析upstream中的主机名，解析不到会直接启动失败（不支持运行时动态感知后端实例变化），因此docker-compose中nginx必须depends_on两个后端实例。这是Nginx开源版的已知限制，生产环境可通过Nginx Plus或consul-template解决。')

# 4.4.7
make_heading(doc, '4.4.7 Web 可视化前端模块', 3)
make_para(doc, 'Web可视化前端模块对应功能模块M7，物理实现为独立工程frontend/，采用React 19 + Vite构建。构建产物（dist目录）由Nginx容器以只读挂载方式托管，用户通过浏览器访问http://localhost即可使用全部功能。')
make_para(doc, '前端技术栈包含：React 19提供组件化开发与React Compiler自动优化；Vite提供秒级热更新与开箱即用的构建配置；Ant Design 6提供企业级后台组件（表格、表单、标签、分页、抽屉），与设备台账、告警列表等管理场景高度契合；ECharts负责图表可视化（电量统计、告警趋势等统计图表）；Leaflet承载设备GIS分布地图（已接入高德瓦片底图），对应实时态势展示需求。')
make_para(doc, '前端功能页面包括：登录与角色权限页（JWT认证）、设备列表与台账管理页（5秒自动刷新、设备增删改停用）、设备地图页（实时位置、Leaflet地图）、告警列表页（多条件筛选、批量处置、服务端分页）、巡检任务管理页（建单/下发/重发/取消、进度条与回执时间轴）、巡检影像管理页（列表/预览/下载）、统计分析页（KPI卡片+ES聚合图表）、用户管理页（增删改、重置密码、启停）。所有API请求经Nginx网关转发至后端，不直连数据库或中间件。')

# 4.5
make_heading(doc, '4.5 数据库设计', 2)

# 4.5.1
make_heading(doc, '4.5.1 MongoDB 数据表设计', 3)
make_para(doc, '本系统采用MongoDB作为结构化数据存储，数据库名为uav_platform。根据数据特征分为两类集合：普通集合（支持频繁更新）与时序集合（支持高吞吐写入与自动过期）。集合设计如表4-4所示。')

t44_data = [
    ["集合名", "类型", "用途", "关键字段", "索引", "过期策略"],
    ["device_status", "普通集合", "设备台账与实时状态", "deviceNo, type, status, battery, lastHeartbeat, location", "{status:1}", "无"],
    ["task", "普通集合", "巡检任务台账（可反复更新状态/进度）", "taskId(_id), deviceNo, taskType, status, createTime, targetArea, description", "{status:1, createTime:-1}, {deviceNo:1, createTime:-1}", "无"],
    ["alarm", "普通集合", "告警记录与处置留痕", "alarmId(_id), deviceNo, alarmType, level, lat, lng, eventTime, handleStatus, handleBy", "{alarmType:1, eventTime:-1}, {handleStatus:1}", "无"],
    ["media_meta", "普通集合", "巡检影像元数据", "fileId, deviceNo, mediaType, hdfsPath, uploaded", "{uploaded:1}", "无"],
    ["telemetry", "时序集合", "巡检GPS轨迹", "eventTime, deviceNo(元数据), lat, lng", "自动（timeField索引）", "7天"],
    ["sensor_data", "时序集合", "环境传感器读数", "eventTime, deviceNo(元数据), temperature, humidity, gasValue, deviceTemp", "自动（timeField索引）", "7天"],
    ["task_log", "时序集合", "任务执行回执流水（只追加）", "eventTime, deviceNo(元数据), taskId, ackStatus, result", "{taskId:1}", "7天"],
]
make_table(doc, t44_data)
make_caption(doc, '表4-4 MongoDB集合设计表')

make_para(doc, '时序集合的设计要点是：以eventTime作为时间字段（必须是Date类型），以deviceNo作为元数据字段（同设备归一组，便于压缩），粒度为秒级，并设置expireAfterSeconds=604800（7天自动过期）。这种设计使高频写入的GPS轨迹、传感器读数和任务回执在7天后自动清理，避免存储空间无限增长，同时利用MongoDB时序集合的底层列式压缩降低存储开销。')
make_para(doc, 'task集合与task_log集合的分离体现了"台账与流水分离"的设计思想：task集合存储任务当前状态（可反复更新，如"已下发→执行中→已完成"），task_log集合存储任务执行回执的时序流水（只追加不修改），两者通过taskId关联。这种分离避免了在同一集合上同时进行高频写入和状态更新带来的锁竞争。')

# 4.5.2
make_heading(doc, '4.5.2 Elasticsearch 索引设计', 3)
make_para(doc, 'Elasticsearch在系统中仅建立告警事件索引（alarm），用于告警的全文检索与聚合统计。索引文档由Java实体类AlarmDoc映射，文档结构如表4-5所示。')

t45_data = [
    ["字段名", "类型", "说明", "检索用途"],
    ["alarmId", "keyword（_id）", "告警唯一标识，用作ES文档主键实现幂等写入", "去重"],
    ["deviceNo", "keyword", "设备编号", "按设备筛选"],
    ["alarmType", "keyword", "告警类型（如区域入侵、心跳超时、设备故障）", "按类型筛选"],
    ["level", "keyword", "告警级别（提示/一般/严重）", "按级别筛选"],
    ["lat / lng", "double", "告警发生地经纬度", "可扩展为geo_point支持地理检索"],
    ["mediaFileId", "keyword", "关联影像文件ID", "关联影像预览"],
    ["description", "text", "告警描述（全文）", "全文检索"],
    ["eventTime", "long（时间戳）", "设备产生告警的时间", "时间范围筛选与排序"],
    ["ingestTime", "long（时间戳）", "平台接收告警的时间", "延迟分析"],
    ["handleStatus", "keyword", "处置状态（PENDING/RESOLVED/FALSE_ALARM）", "按状态筛选"],
    ["handleBy", "keyword", "处置人用户名", "处置统计"],
    ["handleTime", "long（时间戳）", "处置时间", "处置时效分析"],
    ["handleRemark", "text", "处置备注", "全文检索"],
]
make_table(doc, t45_data)
make_caption(doc, '表4-5 Elasticsearch告警索引字段设计表')

make_para(doc, '告警索引采用"双写"策略：平台消费告警消息后，将同一份告警数据同时写入MongoDB的alarm集合（主存储，负责事务性读写与状态更新）和Elasticsearch的alarm索引（检索索引，负责全文检索与聚合分析）。双写保证了数据一致性，同时让两种存储各司其职：MongoDB的查询能力满足按编号、按状态的精确查询，Elasticsearch的倒排索引和聚合能力满足按类型、级别、时间范围的多条件筛选与统计分析。')
make_para(doc, '索引的字段类型设计遵循以下原则：deviceNo、alarmType、level、handleStatus等用于精确匹配和聚合的字段设为keyword类型；description、handleRemark等需要分词检索的字段设为text类型；eventTime、ingestTime、handleTime等时间字段以时间戳（long）存储，便于范围查询和排序；lat、lng保留为double类型，后续可扩展为geo_point类型以支持"查找某坐标半径范围内的告警"等地理检索场景。')

# 4.6
make_heading(doc, '4.6 部署方案设计', 2)
make_para(doc, '本系统采用Docker Compose单机容器编排方案，依据课程指导说明书"不要求物理多机集群，单机Docker容器模拟多节点集群即可"的要求，通过容器化部署模拟分布式集群环境。部署拓扑如图4-2所示。')

# Insert deployment image
img_path2 = os.path.join(IMG_DIR, "部署图.png")
if os.path.exists(img_path2):
    make_image(doc, img_path2, 5.5)
    make_caption(doc, "图4-2 Docker集群部署拓扑图")
else:
    make_para(doc, "[图4-2 Docker集群部署拓扑图]")

make_para(doc, '部署方案的核心特征是"设备侧与平台侧分离"。仿真端代表园区中真实的无人机/机器狗（系统外部设备），独立运行，不纳入Docker Compose编排（通过profile控制，默认不启动）；平台侧用Docker Compose统一编排9个容器，包括6类中间件容器、2个后端服务实例和1个Nginx网关。各容器配置如表4-6所示。')

t46_data = [
    ["容器名", "镜像版本", "集群角色", "宿主机端口", "说明"],
    ["hadoop-namenode", "bde2020/hadoop-namenode:2.0.0-hadoop3.2.1", "HDFS NameNode", "9870, 9000", "影像存储元数据节点，WebUI与HDFS协议端口"],
    ["hadoop-datanode", "bde2020/hadoop-datanode:2.0.0-hadoop3.2.1", "HDFS DataNode", "9864", "影像数据节点，副本数1"],
    ["mongodb", "mongo:8.3.9", "单节点", "27017", "结构化数据存储，WiredTiger缓存512MB"],
    ["kafka", "apache/kafka:4.3.1", "Broker+Controller（KRaft）", "19092", "消息中间件，双listener解决容器内外寻址"],
    ["elasticsearch", "docker.elastic.co/elasticsearch:9.5.3", "单节点", "9200", "告警索引与检索，堆内存512MB，内存限制1GB"],
    ["kibana", "docker.elastic.co/kibana:9.5.3", "单实例", "5601", "检索可视化仪表盘，运维旁路"],
    ["platform-service-1", "自构建镜像", "业务服务实例1", "无（仅容器内）", "消息消费+存储写入+REST接口，不映射端口"],
    ["platform-service-2", "自构建镜像", "业务服务实例2", "无（仅容器内）", "与实例1配置相同，演示负载均衡"],
    ["nginx-gateway", "nginx:1.30-alpine", "反向代理/静态托管", "80", "统一对外入口，托管前端+API代理"],
]
make_table(doc, t46_data)
make_caption(doc, '表4-6 Docker容器部署配置表')

make_para(doc, '配置策略采用"同一份代码、两套地址"方案：本地开发使用application.properties默认值（localhost:xxxx），容器运行通过环境变量覆盖为服务名（kafka:9092、mongodb:27017、hadoop-namenode:9870等），由Docker内建DNS解析。环境变量使用Spring Boot标准命名（如SPRING_MONGODB_URI、SPRING_KAFKA_BOOTSTRAP_SERVERS），通过宽松绑定直接覆盖配置，无需占位符解析。')
make_para(doc, '启动顺序按依赖导向编排：HDFS/MongoDB/Kafka/Elasticsearch等中间件容器先启动，platform-service依赖这些中间件（depends_on声明），Nginx最后启动并依赖两个后端实例。所有容器设置restart: unless-stopped策略，中间件容器通过healthcheck健康检查确保就绪。数据卷（hdfs-namenode、hdfs-datanode、mongodb-data、kafka-data、es-data）由Docker管理，删除容器不丢数据，便于反复重置与排错。')

# 4.7
make_heading(doc, '4.7 本章小结', 2)
make_para(doc, '本章从系统设计原则、总体架构、技术选型、模块划分、数据库设计与部署方案六个方面完成了系统的总体设计。')
make_para(doc, '在架构层面，系统采用七层分层架构，以"数据上报→消息流转→分布式存储/检索→可视化展示"为数据驱动主线，各层通过消息协议或REST接口单向依赖交互，无循环依赖。消息中间件将"上报—下发"业务闭环从直接调用中切开，实现生产者与消费者的时空解耦。')
make_para(doc, '在技术选型层面，选定Apache/Java/大数据生态技术栈——Kafka、HDFS、Elasticsearch均为Java实现且有官方Java客户端，后端采用Java+Spring Boot实现从设备上报到检索查询的一条Java技术链，所有中间件以Docker镜像固定版本交付，规避版本兼容风险。')
make_para(doc, '在模块划分层面，按"参与者+用例聚类+技术支撑"将系统划分为7个功能模块与4个技术支撑模块，模块是逻辑单位而非物理单位，判断是否独立进程的依据是"是否需要独立构建与部署"。业务服务采用模块化单体架构，内部按业务领域划分子模块，为后续微服务化预留边界。')
make_para(doc, '在数据存储层面，采用MongoDB与HDFS"两库分离"策略，HDFS存储大文件、MongoDB存储结构化数据，时序集合支持高吞吐写入与自动过期。Elasticsearch建立告警索引，通过"双写"策略与MongoDB各司其职。')
make_para(doc, '在部署层面，Docker Compose单机编排9个容器模拟分布式集群，"同一份代码、两套地址"方案实现本地开发与容器部署的统一，数据卷保障数据持久化。')

# ---- Save ----
doc.save(SAVE_PATH)
print(f"Chapter 4 content written successfully to: {SAVE_PATH}")
