"""Generate the fictitious sample documents used to test the conversion service.

Creates, in the folder of this script:
  quarterly-facilities-report.docx  styles, a table, an embedded chart image, header and footer
  maintenance-budget.xlsx           three sheets, formulas, number formats and a column chart
  project-kickoff.pptx              title, bullets, a table, a native chart and a picture
  floor-plan.png                    a drawn floor plan with labels
  visitor-notice.html               a self-contained HTML page with CSS and a table

Every name, address and figure is invented.

    pip install -r requirements.txt
    python generate_samples.py
"""

from __future__ import annotations

import io
from datetime import date, datetime
from pathlib import Path

import xlsxwriter

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from PIL import Image, ImageDraw, ImageFont
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor as PptColor
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION
from pptx.util import Inches
from pptx.util import Pt as PptPt

OUT = Path(__file__).resolve().parent
COMPANY = "Alder Row Facilities Ltd"
SITES = [
    ("Riverside Office", 412, 398, 96.6),
    ("Elm Park Depot", 188, 171, 91.0),
    ("Canal Street Studio", 96, 95, 99.0),
    ("Westfield Warehouse", 265, 233, 87.9),
]
QUARTERS = ["Q1", "Q2", "Q3", "Q4"]
BUDGET = [
    # category, Q1, Q2, Q3, Q4 planned spend
    ("HVAC servicing", 18400, 12600, 21900, 14300),
    ("Electrical checks", 9200, 8800, 9600, 10100),
    ("Lift maintenance", 7600, 7600, 7600, 7900),
    ("Fire safety", 5400, 6100, 5200, 6800),
    ("Cleaning contracts", 22800, 22800, 23400, 23400),
    ("Grounds and landscaping", 3100, 6900, 7400, 2800),
]
ACTUALS = [
    ("HVAC servicing", 17950, 13820, 22640, 13980),
    ("Electrical checks", 9410, 8650, 9980, 9870),
    ("Lift maintenance", 7600, 8240, 7600, 7900),
    ("Fire safety", 5120, 6380, 5050, 7240),
    ("Cleaning contracts", 22800, 22950, 23400, 23100),
    ("Grounds and landscaping", 2870, 7310, 6980, 2650),
]

BLUE = (46, 84, 132)
GREY = (90, 98, 110)
LIGHT = (232, 237, 243)


def font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    """A TrueType font when one is available, else Pillow's default font."""
    candidates = [
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold else "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default(size)


# ---------------------------------------------------------------- images


def chart_png() -> bytes:
    """A bar chart of completed work orders per site, drawn with Pillow."""
    w, h = 1200, 640
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    d.text((40, 24), "Work orders closed per site, Q3", font=font(34, True), fill=BLUE)
    left, right, top, bottom = 120, w - 60, 110, h - 90
    max_value = 450
    for step in range(0, max_value + 1, 50):
        y = bottom - (bottom - top) * step / max_value
        d.line((left, y, right, y), fill=(220, 224, 230), width=2)
        d.text((left - 70, y - 14), str(step), font=font(22), fill=GREY)
    slot = (right - left) / len(SITES)
    for i, (name, raised, closed, _) in enumerate(SITES):
        x0 = left + i * slot + slot * 0.18
        bw = slot * 0.30
        for j, (value, colour) in enumerate(((raised, (160, 178, 204)), (closed, BLUE))):
            y = bottom - (bottom - top) * value / max_value
            d.rectangle((x0 + j * bw, y, x0 + (j + 1) * bw - 6, bottom), fill=colour)
            d.text((x0 + j * bw + 4, y - 30), str(value), font=font(20), fill=GREY)
        label = name if len(name) < 16 else name.replace(" ", "\n", 1)
        d.multiline_text((x0 - 10, bottom + 12), label, font=font(22), fill=GREY, spacing=2)
    d.rectangle((w - 330, 30, w - 310, 50), fill=(160, 178, 204))
    d.text((w - 300, 26), "Raised", font=font(22), fill=GREY)
    d.rectangle((w - 190, 30, w - 170, 50), fill=BLUE)
    d.text((w - 160, 26), "Closed", font=font(22), fill=GREY)
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def floor_plan_png(path: Path) -> None:
    """A simple office floor plan with room labels and dimensions."""
    w, h = 1600, 1100
    img = Image.new("RGB", (w, h), (250, 250, 247))
    d = ImageDraw.Draw(img)
    d.text((60, 40), "Riverside Office, level 2, floor plan", font=font(40, True), fill=(40, 40, 40))
    d.text((60, 96), f"{COMPANY}  |  Drawing FP-02  |  Scale 1:100  |  Revision C", font=font(26), fill=GREY)
    wall = (50, 50, 50)
    x0, y0, x1, y1 = 100, 170, 1500, 1000
    d.rectangle((x0, y0, x1, y1), outline=wall, width=10)
    rooms = [
        ((x0, y0, 560, 560), "Open office A", "68 m2"),
        ((560, y0, 900, 560), "Meeting room 1", "24 m2"),
        ((900, y0, x1, 560), "Open office B", "92 m2"),
        ((x0, 560, 420, y1), "Kitchen", "21 m2"),
        ((420, 560, 760, y1), "Plant room", "18 m2"),
        ((760, 560, 1100, y1), "Store", "15 m2"),
        ((1100, 560, x1, y1), "Meeting room 2", "30 m2"),
    ]
    fills = [(236, 241, 247), (244, 238, 228), (236, 241, 247), (238, 244, 236), (246, 236, 236), (240, 240, 240), (244, 238, 228)]
    for ((a, b, c, e), name, area), fill in zip(rooms, fills):
        d.rectangle((a + 5, b + 5, c - 5, e - 5), fill=fill)
        d.rectangle((a, b, c, e), outline=wall, width=6)
        cx, cy = (a + c) / 2, (b + e) / 2
        d.text((cx, cy - 18), name, font=font(30, True), fill=(40, 40, 40), anchor="mm")
        d.text((cx, cy + 22), area, font=font(24), fill=GREY, anchor="mm")
    for (a, b) in ((300, 560), (690, 560), (980, 560), (1300, 560)):
        d.rectangle((a - 40, b - 6, a + 40, b + 6), fill=(250, 250, 247))
        d.arc((a - 40, b - 80, a + 40, b), 0, 180, fill=GREY, width=3)
    d.line((x0, y1 + 40, x1, y1 + 40), fill=GREY, width=3)
    d.text(((x0 + x1) / 2, y1 + 62), "28.0 m", font=font(26), fill=GREY, anchor="mm")
    d.ellipse((1430, 1030, 1470, 1070), outline=wall, width=3)
    d.text((1450, 1050), "N", font=font(22, True), fill=wall, anchor="mm")
    img.save(path, "PNG", optimize=True)


# ---------------------------------------------------------------- metadata


def set_core_properties(props, title: str) -> None:
    """Neutral document properties, replacing the values of the library templates."""
    props.title = title
    props.author = COMPANY
    props.last_modified_by = COMPANY
    props.comments = "Fictitious sample for a conversion demo"
    props.subject = ""
    props.keywords = ""
    props.category = ""
    props.revision = 1
    props.created = props.modified = datetime(2026, 10, 5, 9, 0, 0)


# ---------------------------------------------------------------- DOCX


def shade(cell, hex_fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_fill)
    tc_pr.append(shd)


def page_number_field(paragraph) -> None:
    run = paragraph.add_run()
    for kind, text in (("begin", None), (None, "PAGE"), ("end", None)):
        if kind:
            el = OxmlElement("w:fldChar")
            el.set(qn("w:fldCharType"), kind)
        else:
            el = OxmlElement("w:instrText")
            el.set(qn("xml:space"), "preserve")
            el.text = text
        run._r.append(el)


def make_docx(path: Path) -> None:
    doc = Document()
    set_core_properties(doc.core_properties, "Quarterly facilities report, Q3")
    section = doc.sections[0]
    section.page_width, section.page_height = Cm(21.0), Cm(29.7)
    section.left_margin = section.right_margin = Cm(2.2)
    section.top_margin = section.bottom_margin = Cm(2.0)

    styles = doc.styles
    styles["Normal"].font.name = "Calibri"
    styles["Normal"].font.size = Pt(10.5)
    for name, size in (("Title", 24), ("Heading 1", 15), ("Heading 2", 12)):
        styles[name].font.color.rgb = RGBColor(*BLUE)
        styles[name].font.size = Pt(size)

    header = section.header.paragraphs[0]
    header.text = f"{COMPANY}  |  Quarterly facilities report"
    header.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    header.runs[0].font.size = Pt(8)
    header.runs[0].font.color.rgb = RGBColor(*GREY)
    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    footer.add_run("Page ").font.size = Pt(8)
    page_number_field(footer)

    doc.add_paragraph("Quarterly facilities report, Q3", style="Title")
    meta = doc.add_paragraph()
    meta.add_run("Prepared by: ").bold = True
    meta.add_run("Dana Whitfield, Facilities Manager    ")
    meta.add_run("Date: ").bold = True
    meta.add_run(date(2026, 10, 5).strftime("%d %B %Y"))

    doc.add_heading("1. Summary", level=1)
    doc.add_paragraph(
        "This report covers planned and reactive maintenance across the four sites managed by "
        f"{COMPANY} between July and September. Work order volumes rose by 6% on the previous "
        "quarter, mainly because of the cooling demand in August. Overall completion stayed above "
        "the 90% service level target."
    )
    for text in (
        "Completion rate across all sites: 93.1% (target 90%).",
        "Two lift outages at Riverside Office, both resolved within four hours.",
        "Annual fire safety inspection passed at all sites with no major findings.",
    ):
        doc.add_paragraph(text, style="List Bullet")

    doc.add_heading("2. Work orders per site", level=1)
    table = doc.add_table(rows=1, cols=4)
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for cell, text in zip(table.rows[0].cells, ("Site", "Raised", "Closed", "Completion")):
        cell.text = ""
        run = cell.paragraphs[0].add_run(text)
        run.bold = True
        run.font.color.rgb = RGBColor(255, 255, 255)
        shade(cell, "2E5484")
    for i, (name, raised, closed, rate) in enumerate(SITES):
        row = table.add_row().cells
        for j, value in enumerate((name, str(raised), str(closed), f"{rate:.1f}%")):
            row[j].text = value
            if j:
                row[j].paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
            if i % 2:
                shade(row[j], "E8EDF3")
    total = table.add_row().cells
    raised_total = sum(s[1] for s in SITES)
    closed_total = sum(s[2] for s in SITES)
    for j, value in enumerate(("Total", str(raised_total), str(closed_total), f"{100 * closed_total / raised_total:.1f}%")):
        total[j].text = ""
        run = total[j].paragraphs[0].add_run(value)
        run.bold = True
        if j:
            total[j].paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT

    doc.add_paragraph()
    doc.add_picture(io.BytesIO(chart_png()), width=Cm(15.5))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    caption = doc.add_paragraph("Figure 1. Work orders raised and closed per site.")
    caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
    caption.runs[0].italic = True
    caption.runs[0].font.size = Pt(9)

    doc.add_heading("3. Notable issues", level=1)
    doc.add_heading("3.1 Westfield Warehouse cooling", level=2)
    doc.add_paragraph(
        "The roof-mounted cooling unit tripped three times during the August heat. A replacement "
        "compressor was fitted on 22 August. Completion at this site fell to 87.9% while engineers "
        "were redirected to the cooling fault."
    )
    quote = doc.add_paragraph(
        "Action: schedule a pre-summer inspection of all roof plant in May, before the first heat wave.",
        style="Intense Quote",
    )
    quote.alignment = WD_ALIGN_PARAGRAPH.LEFT
    doc.add_heading("3.2 Next quarter", level=2)
    for text in (
        "Replace the emergency lighting batteries at Elm Park Depot.",
        "Tender the cleaning contract for Canal Street Studio.",
        "Survey the Riverside Office roof before winter.",
    ):
        doc.add_paragraph(text, style="List Number")
    doc.save(path)


# ---------------------------------------------------------------- XLSX


def make_xlsx(path: Path) -> None:
    """Workbook with formulas and a chart.

    XlsxWriter stores the result of each formula and the chart data next to the
    formulas, as Excel does when it saves a file. Converters render these cached
    values; they do not recalculate the workbook.
    """
    wb = xlsxwriter.Workbook(str(path))
    wb.set_properties({"title": "Maintenance budget", "author": COMPANY, "company": COMPANY,
                       "comments": "Fictitious sample for a conversion demo",
                       "created": datetime(2026, 10, 5, 9, 0, 0)})
    title = wb.add_format({"bold": True, "font_size": 13, "font_color": "#2E5484"})
    head = wb.add_format({"bold": True, "font_color": "#FFFFFF", "bg_color": "#2E5484", "align": "center", "border": 1, "border_color": "#C8CED6"})
    text = wb.add_format({"border": 1, "border_color": "#C8CED6"})
    money = wb.add_format({"num_format": "#,##0", "border": 1, "border_color": "#C8CED6"})
    money_bold = wb.add_format({"num_format": "#,##0", "bold": True, "border": 1, "border_color": "#C8CED6", "bg_color": "#E8EDF3"})
    bold = wb.add_format({"bold": True, "border": 1, "border_color": "#C8CED6", "bg_color": "#E8EDF3"})
    pct = wb.add_format({"num_format": "0.0%", "border": 1, "border_color": "#C8CED6"})
    pct_bold = wb.add_format({"num_format": "0.0%", "bold": True, "border": 1, "border_color": "#C8CED6", "bg_color": "#E8EDF3"})
    over = wb.add_format({"font_color": "#9C2B23", "border": 1, "border_color": "#C8CED6"})
    under = wb.add_format({"font_color": "#2F6B3A", "border": 1, "border_color": "#C8CED6"})

    def sheet(name: str, rows) -> list[list[float]]:
        ws = wb.add_worksheet(name)
        ws.set_landscape()
        ws.fit_to_pages(1, 0)
        ws.set_column("A:A", 26)
        ws.set_column("B:G", 13)
        ws.write("A1", f"{COMPANY}: maintenance {name.lower()} by category", title)
        ws.write_row("A3", ["Category", *QUARTERS, "Year total", "Share"], head)
        grand = sum(sum(r[1:]) for r in rows)
        first = 4
        for i, (label, *values) in enumerate(rows):
            r = first + i
            ws.write(f"A{r}", label, text)
            ws.write_row(f"B{r}", values, money)
            ws.write_formula(f"F{r}", f"=SUM(B{r}:E{r})", money, sum(values))
            ws.write_formula(f"G{r}", f"=F{r}/$F${first + len(rows)}", pct, sum(values) / grand)
        total = first + len(rows)
        last = total - 1
        ws.write(f"A{total}", "Total", bold)
        for c, col in enumerate("BCDEF"):
            value = sum(r[1 + c] for r in rows) if c < 4 else grand
            ws.write_formula(f"{col}{total}", f"=SUM({col}{first}:{col}{last})", money_bold, value)
        ws.write_formula(f"G{total}", f"=SUM(G{first}:G{last})", pct_bold, 1.0)
        ws.write(f"A{total + 1}", "Quarterly average", text)
        for c, col in enumerate("BCDE"):
            ws.write_formula(f"{col}{total + 1}", f"=AVERAGE({col}{first}:{col}{last})", money, sum(r[1 + c] for r in rows) / len(rows))
        ws.freeze_panes(3, 1)
        if name == "Plan":
            chart = wb.add_chart({"type": "column"})
            for c, col in enumerate("BCDE"):
                chart.add_series({
                    "name": [name, 2, 1 + c],
                    "categories": [name, first - 1, 0, last - 1, 0],
                    "values": [name, first - 1, 1 + c, last - 1, 1 + c],
                    "fill": {"color": ["#A0B2CC", "#6F8BB3", "#2E5484", "#1B3557"][c]},
                })
            chart.set_title({"name": "Planned spend per category and quarter", "name_font": {"size": 12}})
            chart.set_y_axis({"num_format": "#,##0", "major_gridlines": {"visible": True, "line": {"color": "#DDE2E8"}}})
            chart.set_legend({"position": "bottom"})
            chart.set_size({"width": 760, "height": 330})
            ws.insert_chart(f"A{total + 3}", chart)
        return [list(r[1:]) for r in rows]

    plan = sheet("Plan", BUDGET)
    actual = sheet("Actuals", ACTUALS)

    ws = wb.add_worksheet("Variance")
    ws.set_landscape()
    ws.fit_to_pages(1, 0)
    ws.set_column("A:A", 26)
    ws.set_column("B:G", 13)
    ws.write("A1", "Actual minus plan, per quarter", title)
    ws.write_row("A3", ["Category", *QUARTERS, "Year", "Status"], head)
    for i, (label, *_) in enumerate(BUDGET):
        r = 4 + i
        ws.write_formula(f"A{r}", f"=Plan!A{r}", text, label)
        diffs = [a - p for a, p in zip(actual[i], plan[i])]
        for c, col in enumerate("BCDE"):
            ws.write_formula(f"{col}{r}", f"=Actuals!{col}{r}-Plan!{col}{r}", money, diffs[c])
        ws.write_formula(f"F{r}", f"=SUM(B{r}:E{r})", money, sum(diffs))
        status = "Over plan" if sum(diffs) > 0 else "Within plan"
        ws.write_formula(f"G{r}", f'=IF(F{r}>0,"Over plan","Within plan")', over if sum(diffs) > 0 else under, status)
    wb.close()


# ---------------------------------------------------------------- PPTX


def make_pptx(path: Path, floor_plan: Path) -> None:
    prs = Presentation()  # default 10 x 7.5 inch template
    set_core_properties(prs.core_properties, "Riverside Office refurbishment, project kickoff")
    blue = PptColor(*BLUE)

    s = prs.slides.add_slide(prs.slide_layouts[0])
    s.shapes.title.text = "Riverside Office refurbishment"
    s.placeholders[1].text = f"Project kickoff\n{COMPANY}  |  October 2026"
    s.shapes.title.text_frame.paragraphs[0].runs[0].font.color.rgb = blue

    s = prs.slides.add_slide(prs.slide_layouts[1])
    s.shapes.title.text = "Scope"
    body = s.placeholders[1].text_frame
    body.text = "Level 2 open offices and two meeting rooms"
    for line, level in (
        ("New LED lighting with presence detection", 1),
        ("Replacement of the ceiling cassettes", 1),
        ("Kitchen refit and accessible WC", 0),
        ("Works out of hours, office stays open", 0),
        ("Budget approved: 186,000", 0),
    ):
        p = body.add_paragraph()
        p.text = line
        p.level = level

    s = prs.slides.add_slide(prs.slide_layouts[5])
    s.shapes.title.text = "Milestones"
    rows = [("Milestone", "Owner", "Due"), ("Design sign-off", "R. Okafor", "14 Nov"), ("Contractor appointed", "D. Whitfield", "28 Nov"),
            ("Phase 1: open office A", "Site team", "16 Jan"), ("Phase 2: meeting rooms", "Site team", "13 Feb"), ("Handover", "D. Whitfield", "6 Mar")]
    shape = s.shapes.add_table(len(rows), 3, Inches(0.8), Inches(1.8), Inches(8.4), Inches(4.2))
    shape.table.columns[0].width = Inches(4.4)
    for r, row in enumerate(rows):
        for c, value in enumerate(row):
            cell = shape.table.cell(r, c)
            cell.text = value
            run = cell.text_frame.paragraphs[0].runs[0]
            run.font.size = PptPt(18)
            run.font.bold = r == 0
            run.font.color.rgb = PptColor(255, 255, 255) if r == 0 else PptColor(34, 34, 34)
            cell.fill.solid()
            cell.fill.fore_color.rgb = blue if r == 0 else PptColor(*(LIGHT if r % 2 else (255, 255, 255)))

    s = prs.slides.add_slide(prs.slide_layouts[5])
    s.shapes.title.text = "Planned spend per phase"
    data = CategoryChartData()
    data.categories = ["Design", "Phase 1", "Phase 2", "Kitchen", "Contingency"]
    data.add_series("Budget", (14000, 72000, 51000, 33000, 16000))
    data.add_series("Committed", (13200, 64000, 18000, 0, 0))
    chart = s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(0.6), Inches(1.6), Inches(8.8), Inches(5.4), data).chart
    chart.has_legend = True
    chart.legend.position = XL_LEGEND_POSITION.BOTTOM
    chart.legend.include_in_layout = False
    chart.value_axis.tick_labels.number_format = "#,##0"
    chart.value_axis.tick_labels.number_format_is_linked = False
    for series, colour in zip(chart.plots[0].series, ((160, 178, 204), BLUE)):
        series.format.fill.solid()
        series.format.fill.fore_color.rgb = PptColor(*colour)

    s = prs.slides.add_slide(prs.slide_layouts[5])
    s.shapes.title.text = "Level 2 layout"
    s.shapes.add_picture(str(floor_plan), Inches(0.9), Inches(1.6), width=Inches(8.2))
    prs.save(path)


# ---------------------------------------------------------------- HTML


def make_html(path: Path) -> None:
    rows = "\n".join(
        f"      <tr><td>{name}</td><td>{day}</td><td>{time}</td><td>{note}</td></tr>"
        for name, day, time, note in (
            ("Riverside Office", "Monday 9 November", "07:00 to 09:00", "Lifts out of service"),
            ("Elm Park Depot", "Tuesday 10 November", "06:30 to 08:00", "Fire alarm test"),
            ("Canal Street Studio", "Thursday 12 November", "18:00 to 20:00", "Water off"),
            ("Westfield Warehouse", "Friday 13 November", "05:00 to 07:00", "Loading bay closed"),
        )
    )
    path.write_text(f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Planned maintenance notice</title>
<style>
  @page {{ size: A4; margin: 18mm; }}
  body {{ font-family: Arial, Helvetica, sans-serif; color: #222; font-size: 11pt; }}
  h1 {{ color: #2e5484; font-size: 22pt; margin: 0 0 4px; }}
  .sub {{ color: #5a626e; margin-bottom: 18px; }}
  .box {{ border-left: 5px solid #2e5484; background: #e8edf3; padding: 10px 14px; margin: 16px 0; }}
  table {{ border-collapse: collapse; width: 100%; margin-top: 8px; }}
  th {{ background: #2e5484; color: #fff; text-align: left; padding: 7px 9px; }}
  td {{ border-bottom: 1px solid #c8ced6; padding: 7px 9px; }}
  tr:nth-child(even) td {{ background: #f4f6f9; }}
  footer {{ margin-top: 28px; font-size: 9pt; color: #5a626e; }}
</style>
</head>
<body>
  <h1>Planned maintenance notice</h1>
  <div class="sub">{COMPANY} &middot; Week of 9 November 2026</div>
  <p>Routine maintenance is planned at the sites below. Please plan deliveries and visits around these times.</p>
  <div class="box"><strong>Visitors:</strong> sign in at reception and follow the signs to the temporary entrance where lifts are out of service.</div>
  <table>
    <thead><tr><th>Site</th><th>Day</th><th>Time</th><th>Impact</th></tr></thead>
    <tbody>
{rows}
    </tbody>
  </table>
  <footer>Questions: facilities desk, extension 2040. This notice was generated for a demo; all names are fictitious.</footer>
</body>
</html>
""", encoding="utf-8")


def main() -> None:
    plan = OUT / "floor-plan.png"
    floor_plan_png(plan)
    make_docx(OUT / "quarterly-facilities-report.docx")
    make_xlsx(OUT / "maintenance-budget.xlsx")
    make_pptx(OUT / "project-kickoff.pptx", plan)
    make_html(OUT / "visitor-notice.html")
    for f in sorted(OUT.iterdir()):
        if f.suffix in {".docx", ".xlsx", ".pptx", ".png", ".html"}:
            print(f"{f.name:36} {f.stat().st_size:>9,} bytes")


if __name__ == "__main__":
    main()
