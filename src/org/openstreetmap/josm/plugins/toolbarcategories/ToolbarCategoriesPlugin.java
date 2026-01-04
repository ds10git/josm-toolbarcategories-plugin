package org.openstreetmap.josm.plugins.toolbarcategories;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPreset;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.spi.preferences.Config;

public class ToolbarCategoriesPlugin extends Plugin {
  private static final String KEY_LIST_NAMES = ToolbarCategoriesPlugin.class.getSimpleName()+".namesList";
  private static final String KEY_LIST_ITEMS = ToolbarCategoriesPlugin.class.getSimpleName()+".itemsList";
    
  private final List<JPopupMenu> menus;
  private final List<String> menuNames;
  private final List<JButton> toolbarButtons;
  
  private final JMenu categoryAddTo;
  private final JMenuItem categoryCreate;
  
  private JButton componentCurrent;
  private Component separator;
  
  private final ContainerAdapter containerAdapter;
  private final MouseAdapter resetPopupAdapter;
  
  private Thread wait;
  private long lastAdded;
  
  private Thread waitEnabled;
  private long lastEnabledUpdate;
  
  private boolean isLoading;
  private boolean wasLoaded;
  
  private final PropertyChangeListener enabledListener;
  
  public ToolbarCategoriesPlugin(PluginInformation info) {
    super(info);

    menus = new LinkedList<>();
    menuNames = new LinkedList<>();
    toolbarButtons = new LinkedList<>();
    
    categoryAddTo = new JMenu(tr("Add to toolbar category"));
    categoryAddTo.setEnabled(false);
    categoryCreate = new JMenuItem(tr("Create category"));
    categoryCreate.addActionListener(e -> {
      String name = JOptionPane.showInputDialog(MainApplication.getMainFrame(), tr("Please enter name of category"), tr("Name of category?"), JOptionPane.PLAIN_MESSAGE);
      
      if(name != null && !name.isBlank()) {
        for(int i = 0; i < menuNames.size(); i++) {
          if(Objects.equals(menuNames.get(i), name)) {
            addToCategory(menus.get(i), true, true);
            return;
          }
        }
        
        lastAdded = System.currentTimeMillis()+2000;
        menuNames.add(name);
        menus.add(createPopupMenu(new JPopupMenu(), name, false));
        save();
      }
    });
    
    categoryAddTo.add(categoryCreate);
    
    JPopupMenu resetMenu = new JPopupMenu();
    resetMenu.addPopupMenuListener(new PopupMenuListener() {
      @Override
      public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
      @Override
      public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
      
      @Override
      public void popupMenuCanceled(PopupMenuEvent e) {
        componentCurrent = null;        
      }
    });
    
    resetMenu.add(tr("Reset category")).addActionListener(e -> {
      if(componentCurrent != null) {
        int index = menuNames.indexOf(componentCurrent.getAction().getValue(Action.NAME));
        
        menuNames.remove(index);
        menus.remove(index);
        toolbarButtons.remove(index);
        save();
        
        MainApplication.getToolbar().refreshToolbarControl();
      }
    });
    
    resetPopupAdapter = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        checkShowPopupMenu(e);
      }
      
      @Override
      public void mouseReleased(MouseEvent e) {
        checkShowPopupMenu(e);
      }
      
      public void checkShowPopupMenu(MouseEvent e) {
        if(e.isPopupTrigger()) {
          resetMenu.show(e.getComponent(), e.getX(), e.getY());
          componentCurrent = (JButton)e.getComponent();
        }
      }
    };
    
    containerAdapter = new ContainerAdapter() {
      @Override
      public void componentAdded(ContainerEvent e) {
        if(!isLoading && lastAdded < System.currentTimeMillis()) {
          handleComponentAdded();
        }
      }
    };
    
    enabledListener = e -> {
      updateEnabledState();
    };
        
    if(MainApplication.getToolbar() != null) {
      JPopupMenu m = MainApplication.getToolbar().control.getComponentPopupMenu();
      m.addPopupMenuListener(new PopupMenuListener() {
        @Override
        public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
          componentCurrent = null;
          
          Point p = MouseInfo.getPointerInfo().getLocation();
          
          SwingUtilities.convertPointFromScreen(p, MainApplication.getToolbar().control);
          
          Component c = MainApplication.getToolbar().control.getComponentAt(p);
          
          if(c instanceof JButton) {
            Action a = ((JButton)c).getAction();
            
            if(a != null && a.getValue("toolbar") != null) {
              componentCurrent = (JButton)c; 
              updateMenu();
            }
          }
        }
        
        @Override
        public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
          JPopupMenu m = MainApplication.getToolbar().control.getComponentPopupMenu();
          
          if(separator != null) {
            m.remove(separator);
            m.remove(categoryAddTo);
            separator = null;
          }
        }
        
        @Override
        public void popupMenuCanceled(PopupMenuEvent e) {}
      });
    }
  }
  
  @Override
  public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
    if(oldFrame != null) {
      MainApplication.getToolbar().control.removeContainerListener(containerAdapter);
    }
    
    categoryAddTo.setEnabled(newFrame != null);
    
    if(newFrame != null) {
      MainApplication.getToolbar().control.addContainerListener(containerAdapter);
    }
    
    if(!wasLoaded) {
      load();
    }
  }
  
  private JPopupMenu createPopupMenu(JPopupMenu m, String name, boolean save) {
    ToolbarCategoryAction popupAction = new ToolbarCategoryAction(m, name, componentCurrent.getIcon());
    JToolBar toolbar = MainApplication.getToolbar().control;
    
    int index = removeCurrentComponentFromToolbar(true);
    
    JButton component = (JButton)toolbar.add(popupAction);
    component.setEnabled(componentCurrent.isEnabled());
    
    if(Objects.equals(component.getIcon().getClass().getCanonicalName(),"org.openstreetmap.josm.plugins.multilinetoolbar.MultiLineToolbarPlugin.CompoundIcon")) {
      component.setDisabledIcon(componentCurrent.getDisabledIcon());
    }
    
    popupAction.setParent(component);
    component.addMouseListener(resetPopupAdapter);
    toolbarButtons.add(component);
    
    if(index != -1) {
      toolbar.remove(component);
      toolbar.add(component, index);
    }
        
    addToCategory(m, false, save);
    toolbar.repaint();
    
    return m;
  }
  
  private int removeCurrentComponentFromToolbar(boolean remove) {
    int index = -1;
    
    if(remove && componentCurrent != null) {
      JToolBar toolbar = MainApplication.getToolbar().control;
      
      for(int i = 0; i < toolbar.getComponentCount(); i++) {
        if(Objects.equals(toolbar.getComponent(i), componentCurrent)) {
          toolbar.remove(i);
          index = i;
          break;
        }
      }
    }
    
    return index;
  }
  
  private JMenu createMenu(JMenu menu) {
    JMenu m = new JMenu(menu.getAction());
    m.setText(menu.getText());
    
    for(int i = 0; i < menu.getMenuComponentCount(); i++) {
      if(menu.getMenuComponent(i) instanceof JMenu) {
        m.add(createMenu((JMenu)menu.getMenuComponent(i)));
      }
      else if(menu.getMenuComponent(i) instanceof JMenuItem) {
        JMenuItem item = (JMenuItem)menu.getMenuComponent(i);
        m.add(item.getAction()).setText(item.getText());
        item.addPropertyChangeListener("enabled", enabledListener);
      }
      else if(menu.getMenuComponent(i) instanceof JPopupMenu.Separator) {
        m.addSeparator();
      }
    }
    
    return m;
  }
  
  private void addToCategory(JPopupMenu menu, boolean remove, boolean save) {
    removeCurrentComponentFromToolbar(remove);
    
    Action a = componentCurrent.getAction();
    
    JMenuItem item = null;
    
    if(a instanceof TaggingPresetMenu) {
      item = menu.add(createMenu(((TaggingPresetMenu)a).menu));
    }
    else {
      item = menu.add(a);
      
      if(a instanceof TaggingPreset) {
        item.setText(((TaggingPreset)a).getLocaleName());
      }
    }
    
    item.addPropertyChangeListener("enabled", enabledListener);
    
    componentCurrent = null;
    
    if(save) {
      save();
    }
  }
  
  private synchronized void updateEnabledState() {
    lastEnabledUpdate = System.currentTimeMillis();
    
    if(waitEnabled == null || !waitEnabled.isAlive()) {
      waitEnabled = new Thread() {
        public void run() {
          while(System.currentTimeMillis()-lastEnabledUpdate < 50) {
            try {
              Thread.sleep(20);
            } catch (InterruptedException e) {}
          }
          
          for(int k = 0; k < menus.size(); k++) {
            JPopupMenu m = menus.get(k);
            AtomicBoolean enabled = new AtomicBoolean();
            
            for(int i = 0; i < m.getComponentCount(); i++) {
              if(m.getComponent(i).isEnabled()) {
                enabled.set(true);
              }
            }
            
            final int index = k;
            
            SwingUtilities.invokeLater(() -> {
              toolbarButtons.get(index).setEnabled(enabled.get());
            });
          }
        }
      };
      waitEnabled.start();
    }
  }
  
  private void updateMenu() {
    categoryAddTo.removeAll();
    
    JPopupMenu m = MainApplication.getToolbar().control.getComponentPopupMenu();
    separator = m.add(new JPopupMenu.Separator(), 0);
    m.add(categoryAddTo, 0);
    
    for(int i = 0; i < Math.min(menuNames.size(),menus.size()); i++) {
      JMenuItem category = new JMenuItem(menuNames.get(i));
      JPopupMenu menu = menus.get(i);
      category.addActionListener(e -> {
        addToCategory(menu, true, true);
        MainApplication.getToolbar().control.repaint();
      });
      
      categoryAddTo.add(category);
    }
    
    if(categoryAddTo.getMenuComponentCount() > 0) {
      categoryAddTo.addSeparator();
    }
    
    categoryAddTo.add(categoryCreate);
  }
  
  private synchronized void load() {
    if(!isLoading) {
      isLoading = true;
      menuNames.clear();
      menus.clear();
     
      menuNames.addAll(Config.getPref().getList(KEY_LIST_NAMES, Collections.emptyList()));
      
      List<Integer> removeNames = new LinkedList<>();
      
      if(!menuNames.isEmpty()) {
        JToolBar toolbar = MainApplication.getToolbar().control;
        
        List<List<String>> itemList = Config.getPref().getListOfLists(KEY_LIST_ITEMS);
        
        for(int j = 0; j < menuNames.size(); j++) {
          List<String> list = itemList.get(j);
          JPopupMenu m = new JPopupMenu();
          
          for(int i = 0; i < list.size(); i++) {
            String actionId = list.get(i);
            
            for(int k = 0; k < toolbar.getComponentCount(); k++) {
              Component c = toolbar.getComponent(k);
              
              if(c instanceof JButton && ((JButton)c).getAction() != null && Objects.equals(((JButton)c).getAction().getValue("toolbar"), actionId)) {
                componentCurrent = (JButton)c;
                
                if(m.getComponentCount() == 0) {
                  menus.add(createPopupMenu(m, menuNames.get(j), false));
                }
                else {
                  addToCategory(menus.get(j), true, false);
                }
                
                componentCurrent = null;
                break;
              }
            }
          }
          
          if(m.getComponentCount() == 0) {
            removeNames.add(j);
          }
        }
        
        for(int i = removeNames.size()-1; i >= 0; i--) {
          menuNames.remove((int)removeNames.get(i));
          menus.remove((int)removeNames.get(i));
        }        
      }
      
      wasLoaded = true;
      isLoading = false;
    }
  }
  
  private synchronized void handleComponentAdded() {
    lastAdded = System.currentTimeMillis();
    if(wait == null || !wait.isAlive()) {
      wait = new Thread() {
        @Override
        public void run() {
          while(System.currentTimeMillis() - lastAdded < 200) {
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {}
          }
          
          SwingUtilities.invokeLater(() -> load());
        }
      };
      wait.start();
    }
  }
  
  private void save() {
    Config.getPref().putList(KEY_LIST_NAMES, menuNames);
    
    List<List<String>> itemList = new LinkedList<>();
    
    for(JPopupMenu m : menus) {
      List<String> items = new LinkedList<>();
      itemList.add(items);
      
      for(int i = 0; i < m.getComponentCount(); i++) {
        if(m.getComponent(i) instanceof JMenuItem) {
          Action a = ((JMenuItem)m.getComponent(i)).getAction();
          
          if(a != null && a.getValue("toolbar") instanceof String) {
            items.add((String)a.getValue("toolbar"));
          }
        }
      }
    }
    
    Config.getPref().putListOfLists(KEY_LIST_ITEMS, itemList);
  }
  
  private static final class ToolbarCategoryAction extends AbstractAction {
    private JButton parent;
    private JPopupMenu menu;
    
    public ToolbarCategoryAction(JPopupMenu menu, String name, Icon icon) {
      this.menu = menu;
      putValue("toolbar", ToolbarCategoriesPlugin.class.getSimpleName()+"-"+System.currentTimeMillis()+"_"+Math.random()*10000);
      putValue(Action.NAME, name);
      putValue(Action.SHORT_DESCRIPTION, name);
      putValue(Action.LARGE_ICON_KEY, icon);
    }
    
    public void setParent(JButton parent) {
      this.parent = parent;
    }
    
    @Override
    public void actionPerformed(ActionEvent e) {
      if(parent != null) {
        Point p = parent.getMousePosition();
        menu.show(parent, p.x, p.y);
      }
    }
  }
}
