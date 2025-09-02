package com.RuneLingual;

import com.RuneLingual.Widgets.WidgetsUtilRLingual;
import com.RuneLingual.commonFunctions.Colors;
import com.RuneLingual.commonFunctions.Transformer;
import com.RuneLingual.commonFunctions.Transformer.TransformOption;
import com.RuneLingual.SQL.SqlVariables;
import com.RuneLingual.SQL.SqlQuery;

import com.RuneLingual.nonLatin.GeneralFunctions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;

import javax.inject.Inject;

import lombok.Setter;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.Widget;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.regex.Pattern;
import com.RuneLingual.debug.OutputToFile;
import com.RuneLingual.commonFunctions.Ids;
import com.RuneLingual.RuneLingualConfig.*;

@Slf4j
public class MenuCapture
{
	@Inject
	private Client client;
	@Inject
	private RuneLingualPlugin plugin;

	@Setter
	private boolean debugMessages = true;
	@Inject
	private Transformer transformer;
	@Getter // list of menu options currently being translated by api, periodically checked to see if they are done
	private Set<Pair<MenuEntry, PendingTranslationType>> pendingApiTranslation = new HashSet<>();

	private TransformOption menuOptionTransformOption = TransformOption.TRANSLATE_LOCAL;
	@Inject
	private OutputToFile outputToFile;

	@Inject
	public MenuCapture(RuneLingualPlugin plugin) {
		this.plugin = plugin;
	}
	@Inject
	private WidgetsUtilRLingual widgetsUtilRLingual;

	private final Colors optionColor = Colors.white;

	enum PendingTranslationType {
		OPTION,
		TARGET,
		BOTH
	}

	public void handleOpenedMenu(MenuOpened event){
		pendingApiTranslation.clear();
		MenuEntry[] menus = event.getMenuEntries();
		for (MenuEntry menu : menus) {
            handleMenuEvent(menu);
			Menu subMenu = menu.getSubMenu();
			if (subMenu != null)
				for(MenuEntry subMenuEntry : subMenu.getMenuEntries()) {
					handleMenuEvent(subMenuEntry);
				}
        }
	}

	public void handleMenuEvent(MenuEntry currentMenu) {
		// called whenever a right click menu is opened
		String[] newMenus = translateMenuAction(currentMenu);
		String newTarget = newMenus[0];
		String newOption = newMenus[1];

		// add to pending list if they haven't been translated yet
		boolean isPending = addPendingMenuApiTranslation(currentMenu, newOption, newTarget);
		if (isPending) {
			return;
		}


		// reorder them if it is grammatically correct to do so in that language
		if(plugin.getTargetLanguage().needsSwapMenuOptionAndTarget()) {
			String temp = newOption;
			newOption = newTarget;
			newTarget = temp;
		}

		// swap out the translated menu action and target.
		if(newOption != null) {
			if (newTarget != null && !newTarget.isEmpty()) {
				currentMenu.setTarget(newTarget);
			} else {
				// if target is empty, remove the target part of the menu entry
				currentMenu.setTarget("");
			}
			currentMenu.setOption(newOption);
		}
	}

	public String[] translateMenuAction(MenuEntry currentMenu) {
		/*
		returns: String[] {newTarget, newOption}

		target and option examples
		option: Enable prayer reordering	target: <col=ff9040></col>
		option: Add-10<col=ff9040>		target: <col=ff9040>Pollnivneach teleport</col>
		 */
		MenuAction menuType = currentMenu.getType();

		String menuTarget = currentMenu.getTarget();  // eg. <col=ffff00>Sand Crab<col=ff00>  (level-15)     eg2. <col=ff9040>Dramen staff</col>
		String[] targetWordArray = Colors.getWordArray(menuTarget); // eg. ["Sand Crab", " (level-15)"]
		Colors[] targetColorArray = Colors.getColorArray(menuTarget, Colors.white); // eg. [Colors.yellow, Colors.green]

		String menuOption = currentMenu.getOption(); // doesnt seem to have color tags, always white? eg. Attack
		String[] actionWordArray = Colors.getWordArray(menuOption); // eg. ["Attack"]
		Colors[] actionColorArray = Colors.getColorArray(menuOption, optionColor);



		menuOptionTransformOption = getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());


		String[] result;
		// get translation for both target and option
		if(menuOptionTransformOption == TransformOption.TRANSLATE_API) {
			result = translateGeneralMenu(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray, currentMenu);
		} else if(isWalkOrCancel(menuType)){
			result = translateWalkOrCancel(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray, targetColorArray);
		}
		else if (isPlayerMenu(menuType)){
			result = translatePlayer(menuOption, actionWordArray, actionColorArray, targetWordArray, targetColorArray);
		}
		else if(isNpcMenu(menuType)) { // need to split into npcs with and without level
			result = translateNpc(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray, targetColorArray);
		}
		else if(isObjectMenu(menuType)){
			result = translateObject(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray);
		}
		else if(isItemOnGround(menuType)){ // needs checking
			result = translateGroundItem(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray);
		}
		else if(isItemInWidget(currentMenu)){ // either in inventory or in equipment
			result = translateInventoryItem(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray);
		}
		else if(isWidgetOnSomething(menuType)){ // needs checking
			//printMenuEntry(currentMenu);
			Pair<String, String> results = convertWidgetOnSomething(currentMenu);
			String itemLeft = results.getLeft();
			String entityOnRight = results.getRight();
			String itemTranslation = translateInventoryItem(itemLeft, menuOption, actionWordArray, actionColorArray, Colors.getWordArray(itemLeft))[0];
			String entityTranslation = "";
			if(menuType.equals(MenuAction.WIDGET_TARGET_ON_PLAYER)){
				entityTranslation = translatePlayerTargetPart(Colors.getWordArray(entityOnRight), Colors.getColorArray(entityOnRight, Colors.white));
			} else if(menuType.equals(MenuAction.WIDGET_TARGET_ON_NPC)){
				entityTranslation = translateNpc(entityOnRight, menuOption, actionWordArray, actionColorArray, Colors.getWordArray(entityOnRight), Colors.getColorArray(entityOnRight, Colors.white))[0];
			} else if(menuType.equals(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT)){
				entityTranslation = translateObject(entityOnRight, menuOption, actionWordArray, actionColorArray, Colors.getWordArray(entityOnRight))[0];
			} else if(menuType.equals(MenuAction.WIDGET_TARGET_ON_WIDGET) || menuType.equals(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM)){
				entityTranslation = translateGroundItem(entityOnRight, menuOption, actionWordArray, actionColorArray, Colors.getWordArray(entityOnRight))[0];
			}
			String newTarget = itemTranslation + " -> " + entityTranslation;
			String newOption = translateInventoryItem(itemLeft, menuOption, actionWordArray, actionColorArray, Colors.getWordArray(menuOption))[1];
			result = new String[]{newTarget, newOption};
		} else { // is a general menu
//			log.info("General menu");
//			printMenuEntry(currentMenu);

			// for debug purposes
			//outputToFile.menuTarget(menuTarget,SqlVariables.menuInSubCategory.getValue(), source);
			//outputToFile.menuOption(menuOption,SqlVariables.menuInSubCategory.getValue(), source);
			result = translateGeneralMenu(menuTarget, menuOption, actionWordArray, actionColorArray, targetWordArray, currentMenu);
		}

		// if the translation failed, return as is
		if (result.length < 2){
			return new String[]{menuTarget, menuOption};
		}

		String newTarget = result[0];
		String newOption = result[1];

		return new String[]{newTarget, newOption};
	}

	private String[] translateWalkOrCancel(String menuTarget, String menuOption, String[] actionWordArray, Colors[] actionColorArray, String[] targetWordArray, Colors[] targetColorArray){
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		String newOption, newTarget;
		actionSqlQuery.setEnglish(menuOption);
		actionSqlQuery.setCategory(SqlVariables.categoryValue4Actions.getValue());

		newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);

		if(Colors.removeNonImgTags(menuTarget).isEmpty()) {
			newTarget = "";
		} else {
			TransformOption menuTransformOption = getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());
			if(hasLevel(menuTarget)){
				// if walk has a target with level, its a player
				newTarget = translatePlayerTargetPart(targetWordArray, targetColorArray);
			} else {
				// this shouldnt happen but just in case
				targetSqlQuery.setEnglish(targetWordArray[0]);
				targetSqlQuery.setCategory(SqlVariables.categoryValue4Name.getValue());
				targetSqlQuery.setSubCategory(SqlVariables.subcategoryValue4Menu.getValue());
				// need to split into name and level if it has level
				newTarget = transformer.transform(targetWordArray, targetColorArray, menuTransformOption, targetSqlQuery, false);
			}
		}
		return new String[]{newTarget, newOption};
	}

	private String[] translatePlayer(String menuOption, String[] actionWordArray, Colors[] actionColorArray, String[] targetWordArray, Colors[] targetColorArray){
		//returns String[] {newTarget, newOption}
		//leave name as is (but replace to char image if needed)
		String newTarget = translatePlayerTargetPart(targetWordArray, targetColorArray);
		// set action as usual
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);
		actionSqlQuery.setPlayerActions(menuOption, Colors.white);
		String newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);
		return new String[] {newTarget, newOption};
	}

	private String[] translateNpc(String menuTarget, String menuOption, String[] actionWordArray, Colors[] actionColorArray, String[] targetWordArray, Colors[] targetColorArray) {
		String newTarget, newOption;
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);
		TransformOption npcTransformOption = getTransformOption(this.plugin.getConfig().getNPCNamesConfig(), plugin.getConfig().getSelectedLanguage());
		if(npcTransformOption.equals(TransformOption.AS_IS)){
			newTarget = menuTarget;
		} else if(hasLevel(menuTarget)){
			// if npc has a level, translate the name and level separately
			targetSqlQuery.setNpcName(targetWordArray[0], targetColorArray[0]);
			String targetName = transformer.transform(targetWordArray[0], targetColorArray[0], npcTransformOption, targetSqlQuery, false);
			String targetLevel = getLevelTranslation(targetWordArray[1], targetColorArray[1]);
			newTarget = targetName + targetLevel;
		} else {
			// if npc does not have a level, translate the name only
			targetSqlQuery.setNpcName(menuTarget, targetColorArray[0]);
			targetColorArray = Colors.getColorArray(menuTarget, targetColorArray[0]); //default color is not the same as initial definition
			newTarget = transformer.transform(targetWordArray, targetColorArray, npcTransformOption, targetSqlQuery, false);
		}

		actionSqlQuery.setNpcActions(menuOption, optionColor);
		newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);

		return new String[] {newTarget, newOption};
	}

	private String[] translateObject(String menuTarget, String menuOption, String[] actionWordArray, Colors[] actionColorArray, String[] targetWordArray) {
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);
		targetSqlQuery.setObjectName(menuTarget, Colors.lightblue);
		actionSqlQuery.setObjectActions(menuOption, optionColor);

		Colors[] targetColorArray = Colors.getColorArray(menuTarget, Colors.lightblue); //default color is not the same as initial definition

		TransformOption	objectTransformOption = getTransformOption(this.plugin.getConfig().getObjectNamesConfig(), plugin.getConfig().getSelectedLanguage());

		String newTarget;
		if(objectTransformOption.equals(TransformOption.AS_IS)){
			newTarget = menuTarget;
		} else {
			newTarget = transformer.transform(targetWordArray, targetColorArray, objectTransformOption, targetSqlQuery, false);
		}
		String newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);

		return new String[] {newTarget, newOption};
	}

	private String[] translateGroundItem(String menuTarget, String menuOption, String[] actionWordArray, Colors[] actionColorArray, String[] targetWordArray){
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);
		targetSqlQuery.setItemName(menuTarget, Colors.orange);
		actionSqlQuery.setGroundItemActions(menuOption, optionColor);

		Colors[] targetColorArray = Colors.getColorArray(menuTarget, Colors.orange); //default color is not the same as initial definition

		TransformOption itemTransformOption = getTransformOption(this.plugin.getConfig().getItemNamesConfig(), plugin.getConfig().getSelectedLanguage());
		String newTarget;
		if (itemTransformOption.equals(TransformOption.AS_IS)){
			newTarget = menuTarget;
		} else {
			newTarget = transformer.transform(targetWordArray, targetColorArray, itemTransformOption, targetSqlQuery, false);
		}
		if (menuOption.equals("Use")){// if it is "Use" option, it only looks at first value
			return new String[] {newTarget, ""};
		}
		String newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);
		return new String[] {newTarget, newOption};
	}

	private String[] translateInventoryItem(String menuTarget, String menuOption, String[] actionWordArray, Colors[] actionColorArray, String[] targetWordArray){
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);

		targetSqlQuery.setItemName(menuTarget, Colors.orange);
		actionSqlQuery.setInventoryItemActions(menuOption, optionColor);

		Colors[] targetColorArray = Colors.getColorArray(menuTarget, Colors.orange); //default color is not the same as initial definition

		TransformOption itemTransformOption = getTransformOption(this.plugin.getConfig().getItemNamesConfig(), plugin.getConfig().getSelectedLanguage());
		String newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);
		if (Arrays.equals(targetWordArray, new String[]{"Use"}) && Arrays.equals(actionWordArray, new String[]{"Use"})){
			// it comes from the use item on something menu, and only needs to translate the option
			return new String[] {"", newOption};
		}
		String newTarget = transformer.transform(targetWordArray, targetColorArray, itemTransformOption, targetSqlQuery, false);

		return new String[] {newTarget, newOption};
	}

	// for menus that are none of item, object, npc, player, walk, cancel
	// e.g. "Enable prayer reordering" or "Add-10"
	private String[] translateGeneralMenu(String menuTarget, String menuOption, String[] actionWordArray,
										  Colors[] actionColorArray, String[] targetWordArray, MenuEntry currentMenu){
		String newTarget, newOption;
		// check what widget it is in, then set the source column value accordingly
		String source;
		if(plugin.getConfig().getMenuOptionConfig().equals(ingameTranslationConfig.USE_API) && plugin.getConfig().ApiConfig()){
			source = "";
		} else {
			source = getSourceNameFromMenu(currentMenu);
		}
		SqlQuery actionSqlQuery = new SqlQuery(this.plugin);
		actionSqlQuery.setGenMenuAcitons(menuOption, optionColor);
		actionSqlQuery.setSource(source);

		String optionToTranslate = Transformer.getEnglishColValFromText(menuOption);
		actionSqlQuery.setEnglish(optionToTranslate);

		if (!widgetsUtilRLingual.shouldPartiallyTranslateText(optionToTranslate)) {
			newOption = transformer.transformWithPlaceholders(menuOption, optionToTranslate, menuOptionTransformOption, actionSqlQuery);
		} else {
			optionToTranslate = widgetsUtilRLingual.getMatchingEnColVal4PartialTranslation(optionToTranslate);
			if (optionToTranslate == null) {
				newOption = menuOption;
			} else {
				actionSqlQuery.setEnglish(optionToTranslate);
				newOption = transformer.transformWithPlaceholders(menuOption, optionToTranslate, menuOptionTransformOption, actionSqlQuery);
				if (newOption != null) {
					newOption = plugin.getIds().getPartialTranslationManager().translateString(optionToTranslate, newOption, menuOption, optionColor);
				}
			}
		}
		if (newOption == null){ // if no translation was found, then return as is
			newOption = menuOption;
		}

		// if it didnt find a new option (newOption = menuOption), search for any match
		if(Colors.removeNonImgTags(newOption).equals(menuOption) && menuOptionTransformOption.equals(TransformOption.TRANSLATE_LOCAL)) {
			actionSqlQuery = new SqlQuery(this.plugin);
			actionSqlQuery.setEnglish(menuOption);
			newOption = transformer.transform(actionWordArray, actionColorArray, menuOptionTransformOption, actionSqlQuery, false);
		}

		if(Colors.removeNonImgTags(menuTarget).isEmpty()) { // if it doesnt have a target
			newTarget = "";
		} else if (Colors.removeNonImgTags(menuTarget).matches("(\\d+)")){ // if target is a number, leave as is
			newTarget = menuTarget;
		} else {
			// if it is in the quest tab, the values are in a different category/sub_category
			if(source.equals(SqlVariables.sourceValue4QuestListTab.getValue())) {
				menuTarget = translateQuestName(targetWordArray[0]);
				return new String[]{menuTarget, newOption};
			}
			if(source.equals(SqlVariables.sourceValue4EmotesTab.getValue())) { // emote names are stored in category=interface, subcategory=mainTabs, source=emotesTab
				menuTarget = translateEmoteName(targetWordArray[0]);
				return new String[]{menuTarget, newOption};
			}
			if(source.equals(SqlVariables.sourceValue4MusicTab.getValue())) { // music names are stored in category=interface, subcategory=mainTabs, source=musicTab
				menuTarget = translateMusicName(targetWordArray[0]);
				return new String[]{menuTarget, newOption};
			}
			// if the menu target is a widget not to translate (eg. player name) return as is
			Widget widget = currentMenu.getWidget();
			if (widget != null && this.plugin.getWidgetCapture().isWidgetIdNot2Translate(widget)) {
				return new String[]{menuTarget, newOption};
			}
			SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
			targetSqlQuery.setEnglish(targetWordArray[0]);
			targetSqlQuery.setCategory(SqlVariables.categoryValue4Name.getValue());
			targetSqlQuery.setSubCategory(SqlVariables.subcategoryValue4Menu.getValue());
			targetSqlQuery.setSource(source);
			targetSqlQuery.setColor(Colors.orange);

			TransformOption generalMenuTransformOption = getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());
			Colors[] targetColorArray = Colors.getColorArray(menuTarget, Colors.orange); //default color is not the same as initial definition
			if(targetColorArray.length <= 1){
				newTarget = transformer.transform(targetWordArray, targetColorArray, generalMenuTransformOption, targetSqlQuery, false);
			} else {
				String targetToTranslate = Transformer.getEnglishColValFromText(menuTarget);
				newTarget = transformer.transformWithPlaceholders(menuTarget, targetToTranslate, generalMenuTransformOption, targetSqlQuery);
				if (newTarget == null) { // if no translation was found, then return as is
					newTarget = menuTarget;
				}
			}
		}
		return new String[]{newTarget, newOption};
	}

	private String translateQuestName(String questName) {
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		targetSqlQuery.setEnglish(questName);
		targetSqlQuery.setCategory(SqlVariables.categoryValue4Manual.getValue());
		targetSqlQuery.setSubCategory(SqlVariables.subcategoryValue4Quest.getValue());

		TransformOption generalMenuTransformOption = getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());
		// color is not possible to obtain by simple means, so its just orange for now
		return transformer.transform(questName, Colors.orange, generalMenuTransformOption, targetSqlQuery, false);
	}
	private String translateEmoteName(String emoteName) {
		// emote names are stored in category=interface, subcategory=mainTabs, source=emotesTab
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		targetSqlQuery.setEnglish(emoteName);
		targetSqlQuery.setCategory(SqlVariables.categoryValue4Interface.getValue());
		targetSqlQuery.setSubCategory(SqlVariables.subcategoryValue4MainTabs.getValue());
		targetSqlQuery.setSource(SqlVariables.sourceValue4EmotesTab.getValue());

		TransformOption generalMenuTransformOption = getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());
		return transformer.transform(emoteName, Colors.orange, generalMenuTransformOption, targetSqlQuery, false);
	}
	private String translateMusicName(String musicName) {
		// music names are stored in category=interface, subcategory=mainTabs, source=musicTab
		SqlQuery targetSqlQuery = new SqlQuery(this.plugin);
		targetSqlQuery.setEnglish(musicName);
		targetSqlQuery.setCategory(SqlVariables.categoryValue4Interface.getValue());
		targetSqlQuery.setSubCategory(SqlVariables.subcategoryValue4MainTabs.getValue());
		targetSqlQuery.setSource(SqlVariables.sourceValue4MusicTab.getValue());
		targetSqlQuery.setColor(Colors.orange);

		TransformOption generalMenuTransformOption = getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());
		if(generalMenuTransformOption.equals(TransformOption.TRANSLATE_LOCAL)) {
			// music name can have placeholder values for numbers such as arabian <Num0> for "arabian 2", "arabian 3"
			String textToTranslate = Transformer.getEnglishColValFromText(musicName);
			targetSqlQuery.setEnglish(textToTranslate);
			String newMusic = transformer.transformWithPlaceholders(musicName, textToTranslate, generalMenuTransformOption, targetSqlQuery);
			if (newMusic == null){
				newMusic = musicName;
			}
			return newMusic;
		}
		return transformer.transform(musicName, Colors.orange, generalMenuTransformOption, targetSqlQuery, false);

	}

	private String translatePlayerTargetPart(String[] targetWordArray, Colors[] targetColorArray) {

		//leave name as is (but still give to transformer to replace to char image if needed)
		if(!targetWordArray[0].matches("^<img=.*>$")) { // doesn't have icons before their names
			String playerName = targetWordArray[0];
			String translatedName = transformer.transform(playerName, Colors.white, TransformOption.AS_IS, null, false);
			if (targetWordArray.length == 1) {
				return translatedName;
			} else {
				return translatedName + getLevelTranslation(targetWordArray[1], targetColorArray[1]);
			}
		} else {
			// contains icons before their names, such as clan rank symbols
			StringBuilder newName = new StringBuilder();
			String levelString = "  (level-0)";
			for(int i = 0; i < targetWordArray.length; i++){
				if(i == targetWordArray.length - 1){ // the last element of targetWordArray is always the level part
					levelString = targetWordArray[i];
					break;
				}
				newName.append(targetWordArray[i]);
			}
			String translatedName = transformer.transform(newName.toString(), Colors.white, TransformOption.AS_IS, null, false);
			return translatedName + getLevelTranslation(levelString, targetColorArray[targetWordArray.length - 1]);
		}
	}

	private String getSourceNameFromMenu(MenuEntry menu){
		String source = "";
		Ids ids = this.plugin.getIds();
		if(isChildWidgetOf(ids.getCombatOptionParentWidgetId(), menu)){
			source = SqlVariables.sourceValue4CombatOptionsTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdSkillsTab(),menu)){
			source = SqlVariables.sourceValue4SkillsTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdCharacterSummaryTab(),menu)){
			source = SqlVariables.sourceValue4CharacterSummaryTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdQuestTab(), menu)){
			source = SqlVariables.sourceValue4QuestListTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdAchievementDiaryTab(),menu)){
			source = SqlVariables.sourceValue4AchievementDiaryTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdInventoryTab(),menu)){
			source = SqlVariables.sourceValue4InventTab.getValue();
		}  else if(isChildWidgetOf(ids.getWidgetIdEquipmentTab(),menu)){
			source = SqlVariables.sourceValue4WornEquipmentTab.getValue();
		}else if(isChildWidgetOf(ids.getWidgetIdPrayerTab(),menu)){
			source = SqlVariables.sourceValue4PrayerTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdSpellBookTab(), menu)){
			source = SqlVariables.sourceValue4SpellBookTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdGroupsTab(), menu)){
			source = SqlVariables.sourceValue4GroupTab.getValue();
		} else if(isChildWidgetOf(ids.getFriendsTabParentWidgetId(), menu)){
			source = SqlVariables.sourceValue4FriendsTab.getValue();
		} else if(isChildWidgetOf(ids.getIgnoreTabParentWidgetId(), menu)){
			source = SqlVariables.sourceValue4IgnoreTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdAccountManagementTab(), menu)){
			source = SqlVariables.sourceValue4AccountManagementTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdSettingsTab(), menu)){
			source = SqlVariables.sourceValue4SettingsTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdLogoutTab(), menu)){
			source = SqlVariables.sourceValue4LogoutTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdWorldSwitcherTab(), menu)) {
			source = SqlVariables.sourceValue4WorldSwitcherTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdEmotesTab(), menu)){
			source = SqlVariables.sourceValue4EmotesTab.getValue();
		} else if(isChildWidgetOf(ids.getWidgetIdMusicTab(), menu)){
			source = SqlVariables.sourceValue4MusicTab.getValue();
		}
		//log.info("source: " + source);
		return source;
	}

	private String getLevelTranslation(String levelString, Colors color) {
		// translates combat level, such as "  (level-15)"
		SqlQuery levelQuery = new SqlQuery(this.plugin);
		String level = levelString.replaceAll("[^0-9]", "");
		levelQuery.setPlayerLevel();
		Transformer transformer = new Transformer(this.plugin);

		TransformOption option = getTransformOption(this.plugin.getConfig().getGameMessagesConfig(), plugin.getConfig().getSelectedLanguage());
	//TODO: colours may need adjusting for () and level's digits
		if(plugin.getConfig().getSelectedLanguage().needsCharImages()) // change color to simple color variants. eg: light green to green
			color = color.getSimpleColor();
		String levelTranslation = transformer.transform(levelQuery.getEnglish(), color, option, levelQuery, false);
		String openBracket = transformer.transform("(", color, TransformOption.AS_IS, null, false);
		String lvAndCloseBracket = transformer.transform(level+")", color, TransformOption.AS_IS, null, false);
		return "  " + color.getColorTag() + openBracket  + levelTranslation + color.getColorTag() + lvAndCloseBracket;
	}

	public static TransformOption getTransformOption(ingameTranslationConfig conf, LangCodeSelectableList lang) {
		TransformOption transformOption;
		if(conf.equals(ingameTranslationConfig.USE_LOCAL_DATA)){
			if(lang.hasLocalTranscript()) {
				transformOption = TransformOption.TRANSLATE_LOCAL;
			} else {
				transformOption = TransformOption.AS_IS;
			}
		} else if(conf.equals(ingameTranslationConfig.DONT_TRANSLATE)){
			transformOption = TransformOption.AS_IS;
		} else if(conf.equals(ingameTranslationConfig.USE_API)){
			transformOption = TransformOption.TRANSLATE_API;
		} else {
			transformOption = TransformOption.TRANSLATE_LOCAL;
		}
		return transformOption;
	}

	private void printMenuEntry(MenuEntry menuEntry)
	{
		String target = menuEntry.getTarget();
		String option = menuEntry.getOption();
		MenuAction type = menuEntry.getType();
		log.info("option: " + option + ", target: " + target + ", type: " + type);
	}

	private Pair<String, String> convertWidgetOnSomething(MenuEntry entry)
	{
		String menuTarget = entry.getTarget();
		String[] parts = menuTarget.split(" -> ");
		String itemName = parts[0];
		String useOnName = parts[1];
		return Pair.of(itemName, useOnName);
	}

	private boolean hasLevel(String target)
	{
		// check if target contains <col=(numbers and alphabets)>(level-(*\d)). such as "<col=ffffff>Mama Layla<col=ffff00>(level-3000)"
		Pattern re = Pattern.compile(".+<col=[a-zA-Z0-9]+>\\s*\\(level-\\d+\\)");
		return re.matcher(target).find();
	}

	private boolean isWalkOrCancel(MenuAction action)
	{
		return ((action.equals(MenuAction.CANCEL))
				|| (action.equals(MenuAction.WALK)));
	}

	public boolean isGeneralMenu(MenuEntry menuEntry)
	{
		MenuAction action = menuEntry.getType();
		// checks if current action target is a menu that introduces general actions
		return  (!isItemInWidget(menuEntry) &&
					(action.equals(MenuAction.CC_OP)
						|| action.equals(MenuAction.CC_OP_LOW_PRIORITY)
						|| isWalkOrCancel(action)
						|| action.equals(MenuAction.RUNELITE_OVERLAY)
						|| action.equals(MenuAction.RUNELITE)
					)
		);
	}

	public boolean isObjectMenu(MenuAction action)
	{
		return ((action.equals(MenuAction.EXAMINE_OBJECT))
				|| (action.equals(MenuAction.GAME_OBJECT_FIRST_OPTION))
				|| (action.equals(MenuAction.GAME_OBJECT_SECOND_OPTION))
				|| (action.equals(MenuAction.GAME_OBJECT_THIRD_OPTION))
				|| (action.equals(MenuAction.GAME_OBJECT_FOURTH_OPTION))
				|| (action.equals(MenuAction.GAME_OBJECT_FIFTH_OPTION)));
	}
	public boolean isNpcMenu(MenuAction action)
	{
		return ((action.equals(MenuAction.EXAMINE_NPC))
				|| (action.equals(MenuAction.NPC_FIRST_OPTION))
				|| (action.equals(MenuAction.NPC_SECOND_OPTION))
				|| (action.equals(MenuAction.NPC_THIRD_OPTION))
				|| (action.equals(MenuAction.NPC_FOURTH_OPTION))
				|| (action.equals(MenuAction.NPC_FIFTH_OPTION)));
	}
	public boolean isItemOnGround(MenuAction action)
	{
		return ((action.equals(MenuAction.EXAMINE_ITEM_GROUND))
				|| (action.equals(MenuAction.GROUND_ITEM_FIRST_OPTION))
				|| (action.equals(MenuAction.GROUND_ITEM_SECOND_OPTION))
				|| (action.equals(MenuAction.GROUND_ITEM_THIRD_OPTION))
				|| (action.equals(MenuAction.GROUND_ITEM_FOURTH_OPTION))
				|| (action.equals(MenuAction.GROUND_ITEM_FIFTH_OPTION)));
	}
	public boolean isPlayerMenu(MenuAction action)
	{
		return ((action.equals(MenuAction.PLAYER_FIRST_OPTION))
				|| (action.equals(MenuAction.PLAYER_SECOND_OPTION))
				|| (action.equals(MenuAction.PLAYER_THIRD_OPTION))
				|| (action.equals(MenuAction.PLAYER_FOURTH_OPTION))
				|| (action.equals(MenuAction.PLAYER_FIFTH_OPTION))
				|| (action.equals(MenuAction.PLAYER_SIXTH_OPTION))
				|| (action.equals(MenuAction.PLAYER_SEVENTH_OPTION))
				|| (action.equals(MenuAction.PLAYER_EIGHTH_OPTION))
				|| (action.equals(MenuAction.RUNELITE_PLAYER)));
	}
	public boolean isWidgetOnSomething(MenuAction action)
	{
		return ((action.equals(MenuAction.WIDGET_TARGET_ON_WIDGET))
				|| (action.equals(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT))
				|| (action.equals(MenuAction.WIDGET_TARGET_ON_NPC))
				|| (action.equals(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM))
				|| (action.equals(MenuAction.WIDGET_TARGET_ON_PLAYER)));
	}

	public boolean isItemInWidget(MenuEntry menuEntry){
		MenuAction action = menuEntry.getType();
		String target = menuEntry.getTarget();
		target = Colors.removeNonImgTags(target);
		if(target.isEmpty() || !plugin.getConfig().getSelectedLanguage().hasLocalTranscript()){
			return false;
		}
		if (target.endsWith(" (Members)")) {
			target = target.substring(0, target.length() - 10);
		}


		SqlQuery sqlQuery = new SqlQuery(this.plugin);
		sqlQuery.setItemName(target, Colors.orange);


		return sqlQuery.getMatching(SqlVariables.columnEnglish, false).length > 0 &&
				(action.equals(MenuAction.CC_OP)
						|| action.equals(MenuAction.CC_OP_LOW_PRIORITY)
						|| action.equals(MenuAction.WIDGET_TARGET)
				);

	}

	private boolean isChildWidgetOf(int widgetIdToCheck, MenuEntry menuEntry){
		if(widgetIdToCheck == -1){
			return false;
		}
		Widget widget = client.getWidget(menuEntry.getParam1());

		while(widget != null){
			if(widget.getId() == widgetIdToCheck){
				return true;
			}
			widget = widget.getParent();
		}
		return false;
	}

	private boolean addPendingMenuApiTranslation(MenuEntry currentMenu, String newOption, String newTarget) {
		if (!plugin.getConfig().ApiConfig()){
			return false;
		}
		PendingTranslationType pendingType = null;
		boolean isOptionPending = false;
		boolean isTargetPending = false;
		if (plugin.getConfig().getMenuOptionConfig().equals(ingameTranslationConfig.USE_API)){
			if(!newOption.isEmpty() && !newOption.isBlank()) {
				String oldOption_colTag = currentMenu.getOption();
				boolean haveTranslatedBefore = plugin.getDeepl().getDeeplPastTranslationManager().haveTranslatedBefore(oldOption_colTag);
				if (!haveTranslatedBefore){ // when separating words by colors, if any of the words are not result of translation
					isOptionPending = true;
					pendingType = PendingTranslationType.OPTION;
				}
			}
		}
		if(getMenuTargetOption(currentMenu).equals(TransformOption.TRANSLATE_API)) {
			String oldTarget_colTag = currentMenu.getTarget();
			newTarget = Colors.removeNonImgTags(newTarget);
			boolean haveTranslatedBefore = plugin.getDeepl().getDeeplPastTranslationManager().haveTranslatedBefore(oldTarget_colTag);
			if (!haveTranslatedBefore) {// or when separating words by colors, if any of the words match
				if (!newTarget.isEmpty() && !newTarget.isBlank()) {
					isTargetPending = true;
					pendingType = PendingTranslationType.TARGET;
				}
			}
		}
		if (!isOptionPending){
			currentMenu.setOption(newOption);
		}
		if (!isTargetPending){
			currentMenu.setTarget(newTarget);
		}
		if (isOptionPending && isTargetPending){
			pendingType = PendingTranslationType.BOTH;
		}
		if (pendingType != null){
			pendingApiTranslation.add(Pair.of(currentMenu, pendingType));
		}

		return pendingType != null;
	}

	// check if any api translation is done
	// if it is, replace the menu entry with the translation
	public void handlePendingApiTranslation(){
		if (!plugin.getConfig().ApiConfig() ||
				pendingApiTranslation.isEmpty()){
			return;
		}
		Set<Pair<MenuEntry, PendingTranslationType>> toRemove = new HashSet<>();
        for (Pair<MenuEntry, PendingTranslationType> pair : pendingApiTranslation) {
			MenuEntry menu = pair.getLeft();
            PendingTranslationType type = pair.getRight();
			boolean remove = handlePendingMenu(menu, type);
			if (remove) {
				toRemove.add(pair);
			}
        }
		pendingApiTranslation.removeAll(toRemove);
	}

	// if the menu text contains multiple colors, it won't be updated with this function (need to reopen the menu)
	private boolean handlePendingMenu(MenuEntry menu, PendingTranslationType type){
		String oldOption = menu.getOption();
		String oldTarget = menu.getTarget();
		boolean remove = false;
		String newOption = this.plugin.getDeepl().getDeeplPastTranslationManager().getPastTranslation(oldOption);
		String newTarget = this.plugin.getDeepl().getDeeplPastTranslationManager().getPastTranslation(oldTarget);
		if(plugin.getTargetLanguage().needsCharImages()) {
			Colors targetColor, local_optionColor;
			local_optionColor = Colors.white;
			targetColor = Colors.getColorArray(oldTarget, Colors.orange)[0];
			if(plugin.getTargetLanguage().needsSwapMenuOptionAndTarget()){
				// swap the option and target colors
				local_optionColor = targetColor;
				targetColor = Colors.white;
			}
			if (newTarget != null) {
				newTarget = transformer.stringToDisplayedString(newTarget, local_optionColor);
			}
			if (newOption != null) {
				newOption = transformer.stringToDisplayedString(newOption, targetColor);
			}
		}
		if (type.equals(PendingTranslationType.BOTH) && newOption != null && newTarget != null){
			remove = true;
			menu.setOption(newOption);
			menu.setTarget(newTarget);
			swapOptionTarget(menu);
		} else if (type.equals(PendingTranslationType.OPTION) && newOption != null){
			remove = true;
			menu.setOption(newOption);
			swapOptionTarget(menu);
		} else if (type.equals(PendingTranslationType.TARGET) && newTarget != null){
			remove = true;
			menu.setTarget(newTarget);
			swapOptionTarget(menu);
		}


		return remove;
	}

	private void swapOptionTarget(MenuEntry menu){
		if (!this.plugin.getTargetLanguage().needsSwapMenuOptionAndTarget()){
			return;
		}
		String option = menu.getOption();
		String target = menu.getTarget();
		menu.setOption(target);
		menu.setTarget(option);
	}

	private TransformOption getMenuTargetOption(MenuEntry menuEntry){
		if(isNpcMenu(menuEntry.getType())){
			return getTransformOption(this.plugin.getConfig().getNPCNamesConfig(), plugin.getConfig().getSelectedLanguage());
		}
		if(isObjectMenu(menuEntry.getType())){
			return getTransformOption(this.plugin.getConfig().getObjectNamesConfig(), plugin.getConfig().getSelectedLanguage());
		}
		if(isItemOnGround(menuEntry.getType())){
			return getTransformOption(this.plugin.getConfig().getItemNamesConfig(), plugin.getConfig().getSelectedLanguage());
		}
		if(isItemInWidget(menuEntry)){
			return getTransformOption(this.plugin.getConfig().getItemNamesConfig(), plugin.getConfig().getSelectedLanguage());
		}

		return getTransformOption(this.plugin.getConfig().getMenuOptionConfig(), plugin.getConfig().getSelectedLanguage());
	}

//	private void outputToDump(MenuEntry menu, SqlQuery query){
//		String menuTarget = menu.getTarget();
//		String menuOption = menu.getOption();
//		outputToFile.dumpGeneral(menuTarget, query.getCategory(), query.getSubCategory(), query.getSource());
//		outputToFile.dumpGeneral(menuOption, query.getCategory(), query.getSubCategory(), query.getSource());
//	}

}