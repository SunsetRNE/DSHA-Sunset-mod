package com.deepseekharness.app.skills;

import com.deepseekharness.app.util.SkillDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 测试用：在包内构造技能目录，不触碰真实文件系统。 */
public final class SkillCatalogueFixture {
  private SkillCatalogueFixture() {}

  public static SkillImports.Catalogue of(int unreadable, String... names) throws IOException {
    List<SkillImports.Entry> entries = new ArrayList<>();
    for (String name : names) {
      String text =
          "---\nname: "
              + name
              + "\ndescription: Fixture skill "
              + name
              + " with a description long enough to wrap onto a second line on phones.\n---\n\n# "
              + name
              + "\nRun the documented steps.\n";
      entries.add(
          new SkillImports.Entry(
              name + "/SKILL.md", SkillDocument.parse(text.getBytes(StandardCharsets.UTF_8))));
    }
    return new SkillImports.Catalogue(entries, unreadable);
  }
}
