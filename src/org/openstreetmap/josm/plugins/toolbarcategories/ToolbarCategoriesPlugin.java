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
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
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
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener;

public class ToolbarCategoriesPlugin extends Plugin {
  private static final String KEY_INFO_SHOWN = ToolbarCategoriesPlugin.class.getSimpleName()+".infoShown";
  private static final String KEY_LIST_NAMES = ToolbarCategoriesPlugin.class.getSimpleName()+".namesList";
  private static final String KEY_LIST_ITEMS = ToolbarCategoriesPlugin.class.getSimpleName()+".itemsList";
  
  private static final String KEY_MOUSE_MIDDLE_ENABLED = ToolbarCategoriesPlugin.class.getSimpleName()+".middleMouseButtonForOtherToolbarActions";
  
  private static final String SEPARATOR = "-S-E-P-A-R-A-T-O-R-";
    
  private final List<JPopupMenu> menus;
  private final List<String> menuNames;
  private final List<JButton> toolbarButtons;
  
  private final JMenu categoryAddTo;
  private final JMenuItem categoryCreate;
  
  private JButton componentCurrent;
  private Component separator;
  
  private final JPopupMenu categoryMenu;
  private final ContainerAdapter containerAdapter;
  private final MouseAdapter buttonsAdapter;
  
  private Thread wait;
  private long lastAdded;
  
  private Thread waitEnabled;
  private long lastEnabledUpdate;
  
  private boolean isLoading;
  private boolean wasLoaded;
  private boolean middleMouseButtonForOtherToolbarActions;
  
  private final PreferenceChangedListener prefListener;
  
  private final PropertyChangeListener enabledListener;
  
  public ToolbarCategoriesPlugin(PluginInformation info) {
    super(info);

    menus = new LinkedList<>();
    menuNames = new LinkedList<>();
    toolbarButtons = new LinkedList<>();
    
    middleMouseButtonForOtherToolbarActions = Config.getPref().getBoolean(KEY_MOUSE_MIDDLE_ENABLED, true);
    
    prefListener = e -> {
      boolean oldValue = middleMouseButtonForOtherToolbarActions;
      middleMouseButtonForOtherToolbarActions = Config.getPref().getBoolean(KEY_MOUSE_MIDDLE_ENABLED, true);
      updateMiddleMouseButtonForOtherToolbarActions(oldValue);
    };
    
    categoryAddTo = new JMenu(tr("Add to toolbar category"));
    categoryAddTo.setEnabled(false);
    categoryCreate = new JMenuItem(tr("Create category"));
    categoryCreate.addActionListener(e -> {
      String name = JOptionPane.showInputDialog(MainApplication.getMainFrame(), tr("Please enter name of category"), tr("Name of category?"), JOptionPane.PLAIN_MESSAGE);
      
      if(name != null && !name.isBlank()) {
        for(int i = 0; i < menuNames.size(); i++) {
          if(Objects.equals(menuNames.get(i), name)) {
            addToCategory(menus.get(i), true, true, -1);
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
    
    final JMenu addSeparatorAction = new JMenu(tr("Add separator above"));
    addSeparatorAction.setEnabled(false);
    
    final JMenu removeAction = new JMenu(tr("Remove element"));
    removeAction.setEnabled(false);
    
    categoryMenu = new JPopupMenu();
    categoryMenu.addPopupMenuListener(new PopupMenuListener() {
      @Override
      public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
        if(categoryMenu.getInvoker() instanceof JButton) {
          int index = menuNames.indexOf(((JButton)categoryMenu.getInvoker()).getAction().getValue(Action.NAME));
          
          if(index >= 0) {
            JPopupMenu m = menus.get(index);
            
            for(int i = 1; i < m.getComponentCount(); i++) {
              final int n = i;
              
              if(m.getComponent(i) instanceof JMenuItem) {
                JMenuItem item = (JMenuItem)m.getComponent(n);
                JMenuItem remove = new JMenuItem(item.getText(), item.getIcon());
                remove.addActionListener(a -> {
                  m.remove(n);
                  clearListener();
                  save();
                  MainApplication.getToolbar().refreshToolbarControl();
                });
                removeAction.add(remove);
                
                JMenuItem addSeparator = new JMenuItem(item.getText(), item.getIcon());
                addSeparator.addActionListener(a -> {
                  m.add(new JPopupMenu.Separator(), n);
                  save();
                });
                addSeparatorAction.add(addSeparator);
              }
              else if(m.getComponent(i) instanceof JPopupMenu.Separator) {
                JPopupMenu.Separator sep = new JPopupMenu.Separator();
                sep.addMouseListener(new MouseAdapter() {
                  public void mouseClicked(MouseEvent e) {
                    m.remove(n);
                    categoryMenu.setVisible(false);
                    save();
                  };
                });
                removeAction.add(sep);
                addSeparatorAction.addSeparator();
              }
            }
            
            removeAction.setEnabled(removeAction.getItemCount() > 0);
            addSeparatorAction.setEnabled(removeAction.getItemCount() > 0);
          }
        }
      }
      @Override
      public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
        removeAction.removeAll();
        removeAction.setEnabled(false);
        addSeparatorAction.removeAll();
        addSeparatorAction.setEnabled(false);
      }
      
      @Override
      public void popupMenuCanceled(PopupMenuEvent e) {}
    });
    
    categoryMenu.add(tr("Reset category")).addActionListener(e -> {
      if(categoryMenu.getInvoker() instanceof JButton) {
        int index = menuNames.indexOf(((JButton)categoryMenu.getInvoker()).getAction().getValue(Action.NAME));
        
        if(index >= 0) {
          clearListener();
          
          removeFromLists(index);
          save();
          
          MainApplication.getToolbar().refreshToolbarControl();          
        }
      }
    });
    
    categoryMenu.addSeparator();
    categoryMenu.add(addSeparatorAction);
    categoryMenu.add(removeAction);
    
    containerAdapter = new ContainerAdapter() {
      @Override
      public void componentAdded(ContainerEvent e) {
        if(!isLoading && lastAdded < System.currentTimeMillis() && !(e.getChild() instanceof JSeparator && ((JSeparator)e.getChild()).getOrientation() == JSeparator.HORIZONTAL)) {
          handleComponentAdded();
        }
      }
    };
    
    buttonsAdapter = new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if(SwingUtilities.isMiddleMouseButton(e)) {
          int modifiers = 0;
          
          if((e.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK) == MouseEvent.CTRL_DOWN_MASK) {
            modifiers |= ActionEvent.CTRL_MASK;
          }
          if((e.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) == MouseEvent.SHIFT_DOWN_MASK) {
            modifiers |= ActionEvent.SHIFT_MASK;
          }
          if((e.getModifiersEx() & MouseEvent.ALT_DOWN_MASK) == MouseEvent.ALT_DOWN_MASK) {
            modifiers |= ActionEvent.ALT_MASK;
          }
          if((e.getModifiersEx() & MouseEvent.META_DOWN_MASK) == MouseEvent.META_DOWN_MASK) {
            modifiers |= ActionEvent.META_MASK;
          }
          
          int index = toolbarButtons.indexOf(e.getComponent());
          Action a = null;
          
          if(index >= 0) {
            JMenuItem c = findMenuItem((JMenuItem)menus.get(index).getComponent(0));
            
            if(!(c instanceof JMenu) && c instanceof JMenuItem && c.getAction() != null) {
              a = c.getAction();
            }
          }
          else {
            Point p = MouseInfo.getPointerInfo().getLocation();
            SwingUtilities.convertPointFromScreen(p, MainApplication.getToolbar().control);
            
            Component c = MainApplication.getToolbar().control.getComponentAt(p);
            
            if(c instanceof JButton) {
              a = ((JButton)c).getAction();
              
              if(a instanceof TaggingPresetMenu) {
                JMenuItem m = findMenuItem(((TaggingPresetMenu)a).menu);
                
                if(!(m instanceof JMenu)) {
                  a = m.getAction();
                }
              }
            }
          }
          
          if(a != null) {
            a.actionPerformed(new ActionEvent(e.getComponent(), 0, "", System.currentTimeMillis(), modifiers));
          }
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
  
  private JMenuItem findMenuItem(JMenuItem c) {
    boolean found = true;
    while(found && c instanceof JMenu) {
      found = false;
      
      for(int i = 0; i < ((JMenu)c).getItemCount(); i++) {
        if(((JMenu)c).getItem(i) instanceof JMenuItem) {
          c = (JMenuItem)((JMenu)c).getItem(i);
          found = true;
          break;
        }
      }
    }
    
    return c;
  }
  
  @Override
  public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
    if(oldFrame != null) {
      MainApplication.getToolbar().control.removeContainerListener(containerAdapter);
      Config.getPref().removeKeyPreferenceChangeListener(KEY_MOUSE_MIDDLE_ENABLED, prefListener);
    }
    
    categoryAddTo.setEnabled(newFrame != null);
    
    if(newFrame != null) {
      MainApplication.getToolbar().control.addContainerListener(containerAdapter);
      Config.getPref().addKeyPreferenceChangeListener(KEY_MOUSE_MIDDLE_ENABLED, prefListener);
      
      if(!Config.getPref().getBoolean(KEY_INFO_SHOWN,false)) {
        new Thread() {
          @Override
          public void run() {
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {}
            
            Config.getPref().putBoolean(KEY_INFO_SHOWN, true);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(MainApplication.getMainFrame(), tr("To add a toolbar element to a toolbar category open the context menu on that element and select:\n''{0}''\n\nTo delete a toolbar category open the context menu on that category icon and select:\n''{1}''\n\nThe first action inside a category can directly be accessed with clicking on the category icon with the middle mouse button.\n\nThe same function of the middle mouse button is added to all other toolbar elements.\n\nTo disabled the middle mouse button for the other elements set ''false'' as value for the preference ''{2}''", tr("Add to toolbar category"), tr("Reset category"), KEY_MOUSE_MIDDLE_ENABLED), tr("How to add categories to toolbar?"), JOptionPane.INFORMATION_MESSAGE));
          };
        }.start();
      }
    }
    
    if(!wasLoaded) {
      load();
    }
  }
    
  private JPopupMenu createPopupMenu(JPopupMenu m, String name, boolean save) {
    ToolbarCategoryAction popupAction = new ToolbarCategoryAction(m, name, componentCurrent);
    JToolBar toolbar = MainApplication.getToolbar().control;
    
    int index = removeCurrentComponentFromToolbar(true);
    
    JButton component = (JButton)toolbar.add(popupAction);
    component.setEnabled(componentCurrent.isEnabled());
    
    if(Objects.equals(component.getIcon().getClass().getCanonicalName(),"org.openstreetmap.josm.plugins.multilinetoolbar.MultiLineToolbarPlugin.CompoundIcon")) {
      component.setDisabledIcon(componentCurrent.getDisabledIcon());
    }
    
    popupAction.setParent(component);
    toolbarButtons.add(component);
    component.addMouseListener(buttonsAdapter);
    component.setComponentPopupMenu(categoryMenu);
    
    if(index != -1) {
      toolbar.remove(component);
      toolbar.add(component, index);
    }
        
    addToCategory(m, false, save, -1);
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
  
  private void addToCategory(JPopupMenu menu, boolean remove, boolean save, int n) {
    removeCurrentComponentFromToolbar(remove);
    
    Action a = componentCurrent.getAction();
    
    JMenuItem item = null;
    
    if(a instanceof TaggingPresetMenu) {
      item = menu.add(createMenu(((TaggingPresetMenu)a).menu));
      
      if(n >= 0) {
        menu.remove(item);
        menu.add(item, n);
      }
    }
    else {
      item = menu.add(a);
      
      if(n >= 0) {
        menu.remove(item);
        menu.add(item, n);
      }
      
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
              if(!(m.getComponent(i) instanceof JPopupMenu.Separator) && m.getComponent(i).isEnabled()) {
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
      JMenu category = new JMenu(menuNames.get(i));
      
      JPopupMenu menu = menus.get(i);
      
      for(int k = 1; k < menu.getComponentCount(); k++) {
        final int n = k;
        
        if(menu.getComponent(k) instanceof JMenuItem) {
          JMenuItem item = (JMenuItem)menu.getComponent(n);
          JMenuItem add = new JMenuItem(item.getText(), item.getIcon());
          add.addActionListener(a -> {
            addToCategory(menu, true, true, n);
            MainApplication.getToolbar().control.repaint();
          });
          
          category.add(add);
        }
        else if(menu.getComponent(k) instanceof JPopupMenu.Separator) {
          JPopupMenu.Separator sep = new JPopupMenu.Separator();
          sep.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
              addToCategory(menu, true, true, n);
              category.setPopupMenuVisible(false);
              categoryAddTo.setPopupMenuVisible(false);
              categoryAddTo.getParent().setVisible(false);
              MainApplication.getToolbar().control.repaint();
            };
          });
          category.add(sep);
        }
      }
      
      JMenuItem atTheEnd = new JMenuItem(tr("At the end"));
      atTheEnd.addActionListener(a -> {
        addToCategory(menu, true, true, -1);
        MainApplication.getToolbar().control.repaint();          
      });
      
      category.addSeparator();
      category.add(atTheEnd);
    
      categoryAddTo.add(category);
    }
    
    if(categoryAddTo.getMenuComponentCount() > 0) {
      categoryAddTo.addSeparator();
    }
    
    categoryAddTo.add(categoryCreate);
  }
  
  private void removeFromLists(int index) {
    menuNames.remove(index);
    menus.remove(index);
    toolbarButtons.remove(index);
  }
  
  private void clearLists() {
    clearListener();
    
    menuNames.clear();
    menus.clear();
    toolbarButtons.clear();    
  }
  
  private synchronized void load() {
    if(!isLoading) {
      JToolBar toolbar = MainApplication.getToolbar().control;
      isLoading = true;
      clearLists();
     
      menuNames.addAll(Config.getPref().getList(KEY_LIST_NAMES, Collections.emptyList()));
      
      List<Integer> removeNames = new LinkedList<>();
      
      if(!menuNames.isEmpty()) {
        List<List<String>> itemList = Config.getPref().getListOfLists(KEY_LIST_ITEMS);
        
        for(int j = 0; j < menuNames.size(); j++) {
          List<String> list = itemList.get(j);
          JPopupMenu m = new JPopupMenu();
          
          for(int i = 0; i < list.size(); i++) {
            String actionId = list.get(i);
            
            if(Objects.equals(SEPARATOR, actionId)) {
              m.addSeparator();
            }
            else {
              for(int k = 0; k < toolbar.getComponentCount(); k++) {
                Component c = toolbar.getComponent(k);
                              
                if(c instanceof JButton && ((JButton)c).getAction() != null && Objects.equals(((JButton)c).getAction().getValue("toolbar"), actionId)) {
                  componentCurrent = (JButton)c;
                  
                  if(m.getComponentCount() == 0) {
                    menus.add(createPopupMenu(m, menuNames.get(j), false));
                  }
                  else {
                    addToCategory(menus.get(j), true, false, -1);
                  }
                  
                  componentCurrent = null;
                  break;
                }
              }
            }
          }
          
          if(m.getComponentCount() == 0) {
            removeNames.add(j);
          }
        }
        
        for(int i = removeNames.size()-1; i >= 0; i--) {
          menuNames.remove((int)removeNames.get(i));
          
          if(menus.size() > (int)removeNames.get(i)) {
            menus.remove((int)removeNames.get(i));
          }
        }
      }
      
      updateMiddleMouseButtonForOtherToolbarActions(false);
      
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
  
  private void clearListener() {
    for(JPopupMenu m : menus) {
      for(int i = 0; i < m.getComponentCount(); i++) {
        if(m.getComponent(i) instanceof JMenuItem) {
          m.getComponent(i).removePropertyChangeListener(enabledListener);
        }
      }
    }
    
    for(JButton b : toolbarButtons) {
      b.removeMouseListener(buttonsAdapter);
      b.setComponentPopupMenu(null);
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
        else if(m.getComponent(i) instanceof JPopupMenu.Separator) {
          items.add(SEPARATOR);
        }
      }
    }
    
    Config.getPref().putListOfLists(KEY_LIST_ITEMS, itemList);
  }
  
  private void updateMiddleMouseButtonForOtherToolbarActions(boolean oldValue) {
    JToolBar toolbar = MainApplication.getToolbar().control;
    
    if(oldValue && !middleMouseButtonForOtherToolbarActions) {
      for(int k = 0; k < toolbar.getComponentCount(); k++) {
        Component c = toolbar.getComponent(k);
        
        if(c instanceof JButton && !(((JButton)c).getAction() instanceof ToolbarCategoryAction)) {
          c.removeMouseListener(buttonsAdapter);
        }
      }
    }
    else if(!oldValue && middleMouseButtonForOtherToolbarActions) {
      for(int k = 0; k < toolbar.getComponentCount(); k++) {
        Component c = toolbar.getComponent(k);
        
        if(c instanceof JButton && !(((JButton)c).getAction() instanceof ToolbarCategoryAction)) {
          c.addMouseListener(buttonsAdapter);
        }
      }
    }
  }
  
  private static final class ToolbarCategoryAction extends AbstractAction {
    private JButton parent;
    private JPopupMenu menu;
    
    public ToolbarCategoryAction(JPopupMenu menu, String name, JButton componentCurrent) {
      this.menu = menu;
      putValue("toolbar", ToolbarCategoriesPlugin.class.getSimpleName()+"-"+System.currentTimeMillis()+"_"+Math.random()*10000);
      putValue(Action.NAME, name);
      putValue(Action.SHORT_DESCRIPTION, name);
      putValue(Action.LARGE_ICON_KEY, componentCurrent.getIcon());
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
